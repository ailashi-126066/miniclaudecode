# Source: https://github.com/earendil-works/pi/blob/42a3497d03ad17e308a2299fa824727894f2c0ec/packages/coding-agent/src/core/session-manager.ts
# Adaptation: plain Python messages, provenance/hash, explicit compaction checkpoint and text edits.
"""Canonical branch and compaction projection used by every context consumer."""

from dataclasses import replace

from pycode.core.mcp import active_mcp
from pycode.core.messages import Message, ToolCall
from pycode.core.session import ProjectedContext, ProjectedEntry, SessionEntry, SessionSnapshot
from pycode.core.session_tree import SessionTree
from pycode.core.skills import active_skills
from pycode.core.tool_pairing import repair_tool_pairing
from pycode.core.values import clone_value, freeze_value
from pycode.services.memory_projection import memory_result, project_memory


class ContextProjector:
    PROJECTION_VERSION = 1
    ORDINARY_STATES = frozenset(
        {
            "custom",
            "input_queued",
            "session_metadata",
            "hook_call",
            "hook_result",
            "plan_review",
            "plan_decision",
        }
    )

    def __init__(self):
        self._session_id = None
        self._key = None
        self._cached = None
        self._raw_messages = ()
        self._tree = None

    def incremental(self, snapshot):
        if self._cached is None:
            return None
        if snapshot.revision <= self._key[1]:
            return None
        added = []
        tree = SessionTree(snapshot.index)
        current = snapshot.leaf_id
        while current != self._cached.leaf_id:
            if current is None:
                return None
            entry = tree.get(current)
            if entry is None:
                return None
            if entry.type not in self.ORDINARY_STATES and entry.type not in {
                "message",
                "custom_message",
                "hook_context",
            }:
                return None
            if entry.type == "message" and entry.message.role == "system":
                return None
            if (
                entry.type == "message"
                and entry.message.model_selection is not None
                and entry.message.model_selection != self._cached.selection
            ):
                return None
            if entry.message is not None and memory_result(entry.message):
                return None
            added.append(entry)
            current = entry.parent_id
        added.reverse()
        contributions = []
        tail = []
        for entry in added:
            messages = tuple(freeze_value(message) for message in self.messages_for(entry))
            contributions.append(ProjectedEntry(entry.id, messages))
            tail.extend(messages)
        if tail:
            self._raw_messages = self._raw_messages + tuple(tail)
            messages = repair_tool_pairing(self._raw_messages)
        else:
            messages = self._cached.messages
        return ProjectedContext(
            snapshot.leaf_id,
            self._cached.entries + tuple(contributions),
            messages,
            self._cached.selection,
            self._cached.thinking,
            self._cached.checkpoint,
        )

    def project(self, snapshot: SessionSnapshot) -> ProjectedContext:
        self._tree = SessionTree(snapshot.index)
        if self._session_id != snapshot.header.session_id:
            self._session_id = snapshot.header.session_id
            self._key = None
            self._cached = None
        key = (snapshot.leaf_id, snapshot.revision, self.PROJECTION_VERSION)
        if key == self._key:
            return self._cached
        incremental = self.incremental(snapshot)
        if incremental is not None:
            self._key = key
            self._cached = incremental
            return incremental
        path = SessionTree(snapshot.index).path(snapshot.leaf_id)
        selection = None
        thinking = "off"
        for entry in path:
            if entry.type == "model_change":
                selection = entry.selection
            elif entry.type == "thinking_level_change":
                thinking = entry.data["thinking"]
            elif entry.type == "message" and entry.message.role == "assistant":
                if entry.message.model_selection is not None:
                    selection = entry.message.model_selection
        prompt_snapshot = None
        for entry in path:
            if entry.type == "system_prompt":
                prompt_snapshot = entry.data
            elif entry.type == "compaction":
                prompt_snapshot = entry.data.get("checkpoint", {}).get(
                    "prompt_snapshot", prompt_snapshot
                )
        context_entries = self.context_entries(path)
        edits = {
            entry.data["target_id"]: entry
            for entry in context_entries
            if entry.type == "context_edit"
        }
        contributions = []
        checkpoint = {}
        for index, entry in enumerate(context_entries):
            if entry.type == "compaction" and index > 0:
                messages = ()
            else:
                messages = self.messages_for(entry)
            edit = edits.get(entry.id)
            if edit is not None:
                messages = self.edit_messages(messages, edit.data["replacement"])
            if entry.type == "compaction" and index == 0:
                checkpoint = clone_value(entry.data.get("checkpoint", {}))
            contributions.append(ProjectedEntry(entry.id, messages))
        for entry in path:
            if entry.type == "tool_set_change" and entry.data.get("source") == "effective_model":
                checkpoint["model_tools"] = clone_value(entry.data["tools"])
                checkpoint["harness_system_hash"] = entry.data.get("harness_system_hash", "")
                checkpoint["harness_system_tokens"] = entry.data.get("harness_system_tokens", 0)
        flattened = []
        for contribution in contributions:
            flattened.extend(contribution.messages)
        messages = tuple(flattened)
        raw_messages = messages
        messages = repair_tool_pairing(messages)
        loaded = active_skills(path)
        pinned = []
        memory_records = ()
        memory_source = None
        for entry in path:
            if entry.type == "compaction" and "memories" in entry.data.get("checkpoint", {}):
                memory_records = entry.data["checkpoint"]["memories"]
                memory_source = entry
            elif entry.type == "memory_load":
                memory_records = entry.data["records"]
                memory_source = entry
        messages, memory_records = project_memory(path, messages, memory_records)
        by_message_id = {message.id: message for message in messages}
        contributions = [
            ProjectedEntry(
                item.entry_id,
                tuple(by_message_id.get(message.id, message) for message in item.messages),
            )
            for item in contributions
        ]
        checkpoint.pop("memories", None)
        if memory_records:
            checkpoint["memories"] = clone_value(memory_records)
            lines = [
                "Saved memory references. Current user instructions and permission boundaries "
                "take priority. "
                "Verify project facts against current code; historical cases are references:"
            ]
            for record in memory_records:
                lines.append(
                    f"[{record['scope']} {record['memory_id']}; "
                    f"kind={record.get('kind', 'SemanticMemory')}; "
                    f"saved={record['updated_at']}; verification={record.get('verification')}] "
                    + record["content"]
                )
            pinned.append(
                ProjectedEntry(
                    memory_source.id,
                    (
                        Message(
                            "memory:" + memory_source.id,
                            "system",
                            "\n".join(lines),
                            provider_metadata={"source_type": "memory_load"},
                        ),
                    ),
                )
            )
        plan_state = None
        plan_entry = None
        for entry in path:
            if (
                entry.type == "plan_state"
                and entry.data["session_id"] == snapshot.header.session_id
            ):
                plan_state = entry.data
                plan_entry = entry
        if plan_state is not None and plan_state["mode"] != "completed":
            checkpoint["plan"] = clone_value(plan_state)
            reminder = (
                f"Plan mode={plan_state['mode']}; active Markdown={plan_state['virtual_path']}. "
                f"File exists={plan_state['document_exists']}. "
            )
            if plan_state["mode"] == "planning":
                reminder += (
                    "The project is read only. Only write the exact active Plan Markdown. "
                    "Call ExitPlanMode after writing it to request approval."
                )
            else:
                reminder += (
                    "Execute only the approved Plan snapshot within this run. "
                    "External changes require fresh approval."
                )
            pinned.append(
                ProjectedEntry(
                    plan_entry.id, (Message("plan:" + plan_state["plan_id"], "system", reminder),)
                )
            )
        if prompt_snapshot is not None:
            checkpoint["prompt_snapshot"] = clone_value(prompt_snapshot)
            checkpoint["system_prompt"] = prompt_snapshot["text"]
            source_id = snapshot.leaf_id
            for entry in reversed(path):
                if entry.type in {"system_prompt", "compaction"}:
                    source_id = entry.id
                    break
            pinned.append(
                ProjectedEntry(
                    source_id,
                    (
                        Message(
                            "prompt:" + prompt_snapshot["sha256"],
                            "system",
                            prompt_snapshot["text"],
                            provider_metadata={"source_type": "system_prompt"},
                        ),
                    ),
                )
            )
        mcp = active_mcp(path)
        if mcp is not None:
            checkpoint["mcp"] = clone_value(mcp)
            source = next((entry for entry in reversed(path) if entry.type == "mcp_catalog"), None)
            if source is None:
                source = next(entry for entry in reversed(path) if entry.type == "compaction")
            message = Message(
                "mcp:" + mcp["hash"],
                "system",
                mcp["text"],
                provider_metadata={"source_type": "mcp_catalog"},
            )
            pinned.append(ProjectedEntry(source.id, (message,)))
        catalog = next((entry for entry in reversed(path) if entry.type == "skill_catalog"), None)
        if catalog is not None and catalog.data["text"]:
            message = Message(
                f"catalog:{catalog.id}",
                "system",
                catalog.data["text"],
                provider_metadata={"source_type": "skill_catalog"},
            )
            pinned.append(ProjectedEntry(catalog.id, (message,)))
        for item in loaded:
            body_reference = item.get("message_entry_id")
            skill_text = item.get("text", "")
            if body_reference is not None:
                if any(contribution.entry_id == body_reference for contribution in contributions):
                    continue
                original = SessionTree(snapshot.index).get(body_reference)
                skill_text = original.message.tool_result.text
            source = next(
                (
                    entry
                    for entry in reversed(path)
                    if entry.type == "skill_load"
                    and entry.data["loaded_hash"] == item["loaded_hash"]
                ),
                None,
            )
            if source is None:
                source = next(
                    entry
                    for entry in reversed(path)
                    if entry.type == "compaction"
                    and any(
                        saved["loaded_hash"] == item["loaded_hash"]
                        for saved in entry.data.get("checkpoint", {}).get("skills", ())
                    )
                )
            message = Message(
                f"skill:{item['name']}:{item['loaded_hash']}",
                "system",
                skill_text,
                provider_metadata={"source_type": "skill_load", "skill_name": item["name"]},
            )
            if body_reference is not None:
                original = self._tree.get(body_reference).message
                result = original.tool_result
                call = None
                for previous in path:
                    if previous.message is None:
                        continue
                    for candidate in previous.message.tool_calls:
                        if candidate.id == result.call_id and candidate.name == "read_file":
                            call = candidate
                if call is None:
                    call = ToolCall(result.call_id, "read_file", {"file_path": item["path"]})
                replay = Message("skill-read:" + source.id, "assistant", tool_calls=(call,))
                pinned.append(ProjectedEntry(source.id, (replay, original)))
            else:
                pinned.append(ProjectedEntry(source.id, (message,)))
        pinned_by_id = {}
        for item in pinned:
            pinned_by_id.setdefault(item.entry_id, []).extend(item.messages)
        for item in contributions:
            if item.entry_id in pinned_by_id:
                pinned_by_id[item.entry_id].extend(item.messages)
        contributions = [
            *[ProjectedEntry(entry_id, tuple(items)) for entry_id, items in pinned_by_id.items()],
            *[item for item in contributions if item.entry_id not in pinned_by_id],
        ]
        pinned_messages = []
        for item in pinned:
            pinned_messages.extend(item.messages)
        messages = tuple(pinned_messages) + messages
        if loaded:
            checkpoint["skills"] = clone_value(list(loaded))
        projected = ProjectedContext(
            snapshot.leaf_id, tuple(contributions), messages, selection, thinking, checkpoint
        )
        self._cached = freeze_value(projected)
        self._key = key
        frozen_messages = {}
        for message in self._cached.messages:
            frozen_messages[message.id] = message
        raw = []
        for item in pinned:
            for message in item.messages:
                raw.append(frozen_messages.get(message.id, freeze_value(message)))
        for message in raw_messages:
            raw.append(frozen_messages.get(message.id, freeze_value(message)))
        self._raw_messages = tuple(raw)
        return self._cached

    def context_entries(self, path: tuple[SessionEntry, ...]) -> tuple[SessionEntry, ...]:
        for index in range(len(path) - 1, -1, -1):
            entry = path[index]
            if entry.type == "fork_context":
                allowed = set(entry.data["source_entry_ids"])
                selected = []
                for previous in path[:index]:
                    if previous.id in allowed:
                        selected.append(previous)
                selected.extend(path[index:])
                path = tuple(selected)
                break
        latest = None
        for index, entry in enumerate(path):
            if entry.type == "compaction":
                latest = index
        if latest is None:
            return path
        compaction = path[latest]
        kept_id = compaction.data["first_kept_entry_id"]
        selected = [compaction]
        keeping = False
        for entry in path[:latest]:
            if entry.id == kept_id:
                keeping = True
            if keeping and not (entry.type == "message" and entry.message.role == "system"):
                selected.append(entry)
        selected.extend(path[latest + 1 :])
        return tuple(selected)

    def messages_for(self, entry: SessionEntry) -> tuple[Message, ...]:
        if entry.type == "message":
            return (freeze_value(entry.message),)
        if entry.type == "input_expired":
            original = self._tree.get(entry.data["input_entry_id"])
            for saved in self._tree.path(entry.parent_id):
                if saved.type == "message" and saved.message.id == original.message.id:
                    return ()
            return (
                Message(
                    "recovered-input:" + original.id,
                    "user",
                    "[Queued instruction recovered after interruption; old execution expired]\n"
                    + original.message.text,
                    provider_metadata={
                        "source_type": "input_expired",
                        "input_entry_id": original.id,
                    },
                ),
            )
        if entry.type in {"custom_message", "hook_context"}:
            return (
                Message(
                    entry.id,
                    "custom",
                    entry.data["text"],
                    timestamp=entry.timestamp,
                    provider_metadata={"source_type": entry.type},
                ),
            )
        if entry.type in {"compaction", "branch_summary"}:
            summary = Message(
                f"summary:{entry.id}",
                "custom",
                entry.data["summary"],
                timestamp=entry.timestamp,
                provider_metadata={"source_type": entry.type},
            )
            prompt = entry.data.get("checkpoint", {}).get("system_prompt")
            if (
                entry.type == "compaction"
                and prompt is not None
                and not entry.data.get("checkpoint", {}).get("prompt_snapshot")
            ):
                return (
                    Message(f"system:{entry.id}", "system", prompt, timestamp=entry.timestamp),
                    summary,
                )
            return (summary,)
        return ()

    def edit_messages(
        self, messages: tuple[Message, ...], replacement: str | None
    ) -> tuple[Message, ...]:
        if replacement is None:
            return ()
        edited = []
        for message in messages:
            if message.role == "tool":
                edited.append(
                    replace(message, tool_result=replace(message.tool_result, text=replacement))
                )
            elif message.role == "assistant":
                edited.append(replace(message, text=replacement, thinking=(), tool_calls=()))
            elif message.role in {"user", "custom"}:
                edited.append(replace(message, text=replacement, attachments=()))
            else:
                edited.append(message)
        return tuple(edited)
