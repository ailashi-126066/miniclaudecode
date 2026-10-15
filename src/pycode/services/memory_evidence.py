"""Capture immutable sources from the original branch and checked output readback."""

from pycode.core.errors import StorageError, ToolError
from pycode.core.hashing import data_hash
from pycode.core.memory_evidence import MemoryEvidenceSnapshot, MemoryEvidenceText
from pycode.core.session_tree import SessionTree
from pycode.core.values import plain_data
from pycode.services.memory_output import output_window


async def capture_memory_evidence(
    snapshot,
    *,
    output_reader=None,
    entry_ids=None,
    ranges=None,
    max_characters=64000,
    output_positions=None,
):
    path = SessionTree(snapshot.index).path(snapshot.leaf_id)
    selected = (
        set(entry_ids) if entry_ids is not None else {e.id for e in path if e.message is not None}
    )
    if not selected <= {e.id for e in path} or len(selected) > 64:
        raise ToolError(
            "memory_source_budget", "Memory sources must be a bounded active-branch range"
        )
    ranges = ranges or {}
    if not set(ranges) <= selected:
        raise ToolError("memory_evidence_invalid", "Memory source ranges have an unknown identity")
    texts = []
    calls = {}
    used = 0
    for entry in path:
        message = entry.message
        if message is None:
            continue
        if message.role == "assistant":
            calls.update({call.id: call for call in message.tool_calls})
        if entry.id not in selected:
            continue
        start, end = ranges.get(entry.id, (0, None))
        if (
            type(start) is not int
            or start < 0
            or (end is not None and (type(end) is not int or end <= start))
        ):
            raise ToolError("memory_evidence_invalid", "Memory source character range is invalid")
        result = message.tool_result
        call = calls.get(result.call_id) if result is not None else None
        if message.role == "tool" and (result is None or call is None or call.name != result.name):
            raise StorageError(
                "memory_source_invalid", "Memory tool evidence has no paired original call"
            )
        if result is not None and result.output_id is not None:
            if output_reader is None:
                raise StorageError(
                    "memory_source_output_missing",
                    "Memory tool evidence requires checked full output readback",
                )
            limit = max_characters - used if end is None else end - start
            text, complete, position = await output_window(
                output_reader,
                result.output_id,
                start,
                limit,
                position=(output_positions or {}).get(entry.id),
            )
            if end is not None and len(text) != end - start:
                raise ToolError(
                    "memory_evidence_invalid", "Memory range exceeds the complete output"
                )
            if end is None and not complete:
                raise ToolError(
                    "memory_source_budget", "Full evidence requires a bounded source window"
                )

        else:
            body = result.text if result is not None else message.text
            if start > len(body) or (end is not None and end > len(body)):
                raise ToolError(
                    "memory_evidence_invalid", "Memory range exceeds its original message"
                )
            text = body[start:end]
        used += len(text)
        if used > max_characters:
            raise ToolError(
                "memory_source_budget", "Memory source bodies exceed the capture budget"
            )
        verified = (
            result is not None
            and result.name == "Bash"
            and result.exit_code == 0
            and not result.is_error
            and not result.details.get("side_effects_uncertain")
            and not result.details.get("output_recovered")
        )
        texts.append(
            MemoryEvidenceText(
                snapshot.header.session_id,
                entry.id,
                message.role,
                text,
                start,
                result.name if result else None,
                plain_data(call.arguments) if call else {},
                verified,
            )
        )
    return MemoryEvidenceSnapshot(
        snapshot.header.session_id,
        snapshot.header.workspace,
        snapshot.leaf_id,
        tuple(e.id for e in path),
        data_hash([plain_data(e) for e in path]),
        tuple(texts),
        next(
            (
                e.message.text
                for e in reversed(path)
                if e.message is not None and e.message.role == "user"
            ),
            "",
        ),
        tuple(
            e.id
            for e in path
            if e.message is not None and e.message.role in {"user", "assistant", "tool"}
        ),
    )
