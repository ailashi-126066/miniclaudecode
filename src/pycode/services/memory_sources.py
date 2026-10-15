"""Read a contiguous bounded source window; unselected characters stay unprocessed."""

from dataclasses import dataclass

from pycode.core.errors import StorageError, ToolError
from pycode.core.session_tree import SessionTree
from pycode.core.summary_source import data_hash
from pycode.core.token_estimate import estimate_text
from pycode.core.values import json_dumps, plain_data
from pycode.services.memory_output import advance_output, output_window


@dataclass(frozen=True)
class MemorySourceWindow:
    entry_ids: tuple[str, ...]
    ranges: dict[str, tuple[int, int]]
    output_positions: dict
    end_output_positions: dict


class MemorySourcePlanner:
    def __init__(self, *, output_reader=None, token_budget=4000, character_limit=16000):
        if token_budget < 128 or character_limit < 1:
            raise ValueError("Memory source budgets must be usable")
        self.output_reader = output_reader
        self.token_budget = token_budget
        self.character_limit = character_limit

    async def read_piece(self, entry, start, position=None):
        message = entry.message
        result = message.tool_result
        if result is None or result.output_id is None:
            body = result.text if result is not None else message.text
            if start > len(body):
                raise StorageError("memory_source_changed", "Source cursor exceeds the message")
            end = min(len(body), start + self.character_limit)
            return body[start:end], end == len(body), None
        if self.output_reader is None:
            raise StorageError("memory_source_output_missing", "Full output readback is required")
        return await output_window(
            self.output_reader, result.output_id, start, self.character_limit, position=position
        )

    def progress(self, snapshot, batches):
        path = SessionTree(snapshot.index).path(snapshot.leaf_id)
        positions = {entry.id: index for index, entry in enumerate(path)}
        eligible = [
            entry.id
            for entry in path
            if entry.message is not None and entry.message.role in {"user", "assistant", "tool"}
        ]
        compatible = []
        for batch in batches.values():
            if (
                batch["session_id"] != snapshot.header.session_id
                or batch["branch_id"] not in positions
            ):
                continue
            prefix = path[: positions[batch["branch_id"]] + 1]
            if data_hash([plain_data(entry) for entry in prefix]) != batch["prefix_hash"]:
                raise StorageError("memory_source_changed", "Processed source prefix changed")
            end = batch["end"]
            if end["entry_id"] not in eligible:
                raise StorageError(
                    "invalid_memory_progress", "Processed cursor is outside its branch"
                )
            compatible.append(
                (
                    eligible.index(end["entry_id"]),
                    end["offset"],
                    {
                        **end,
                        "output_position": batch.get("end_output_positions", {}).get(
                            end["entry_id"]
                        ),
                    },
                )
            )
        return max(compatible, key=lambda value: value[:2])[2] if compatible else None

    async def next(self, snapshot, batches):
        entries = [
            entry
            for entry in SessionTree(snapshot.index).path(snapshot.leaf_id)
            if entry.message is not None and entry.message.role in {"user", "assistant", "tool"}
        ]
        previous = self.progress(snapshot, batches)
        index, start = 0, 0
        if previous is not None:
            index = next(i for i, entry in enumerate(entries) if entry.id == previous["entry_id"])
            start = previous["offset"]
        selected, ranges = [], {}
        positions, end_positions = {}, {}
        remaining = self.token_budget
        while index < len(entries) and len(selected) < 64:
            entry = entries[index]
            position = (
                previous.get("output_position") if previous is not None and not selected else None
            )
            text, complete, beginning = await self.read_piece(entry, start, position)
            if previous is not None and not selected and not text and complete:
                index, start = index + 1, 0
                previous = None
                continue
            overhead = (
                estimate_text(
                    json_dumps({"entry_id": entry.id, "role": entry.message.role, "text": ""})
                )
                + 32
            )
            if remaining <= overhead:
                if not selected:
                    raise ToolError(
                        "memory_source_budget", "Source metadata exceeds the batch budget"
                    )
                break
            low, high = 0, len(text)
            while low < high:
                middle = (low + high + 1) // 2
                cost = estimate_text(json_dumps(text[:middle], ensure_ascii=False)) + overhead
                if cost <= remaining:
                    low = middle
                else:
                    high = middle - 1
            if text and low == 0:
                if not selected:
                    raise ToolError("memory_source_budget", "Source text exceeds the batch budget")
                break
            selected.append(entry.id)
            ranges[entry.id] = (start, start + low)
            if beginning is not None:
                positions[entry.id] = beginning.model_dump(mode="json")
                end_positions[entry.id] = advance_output(beginning, text[:low]).model_dump(
                    mode="json"
                )
            remaining -= estimate_text(json_dumps(text[:low], ensure_ascii=False)) + overhead
            if low < len(text) or not complete:
                break
            index, start = index + 1, 0
            previous = None
        return (
            MemorySourceWindow(tuple(selected), ranges, positions, end_positions)
            if selected
            else None
        )
