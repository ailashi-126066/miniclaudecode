# Source: https://github.com/earendil-works/pi/blob/42a3497d03ad17e308a2299fa824727894f2c0ec/packages/coding-agent/src/core/agent-session.ts
# Adaptation: explicit storage/projector/model/runtime dependencies and linear task settlement.
"""Application commands share the same canonical history and execution owner."""

import asyncio
import logging
from contextlib import asynccontextmanager
from dataclasses import replace
from uuid import uuid4

from pycode.core.errors import DomainError, ModelError, StorageError
from pycode.core.execution_recovery import pending_run_entry
from pycode.core.inputs import InputKind
from pycode.core.messages import Message
from pycode.core.models import ProviderConfig
from pycode.core.revert_state import pending_revert
from pycode.core.runtime import RunResult
from pycode.core.session import EntryDraft
from pycode.core.session_tree import SessionTree
from pycode.core.values import plain_data
from pycode.ports.history import SessionStore
from pycode.ports.runtime import RuntimeLauncher
from pycode.services.context_projector import ContextProjector
from pycode.services.durable_inputs import DurableInputs
from pycode.services.model_control import ModelControl
from pycode.services.restart import recover_conversation
from pycode.services.revert import RevertService
from pycode.services.session_view import SessionView
from pycode.services.sessions import name_session
from pycode.services.skills import SkillService


class AgentSession:
    def __init__(
        self,
        store: SessionStore,
        projector: ContextProjector,
        models: ModelControl,
        launcher: RuntimeLauncher,
        default: ProviderConfig,
        durable_inputs: DurableInputs | None = None,
        revert: RevertService | None = None,
        view: SessionView | None = None,
        skills: SkillService | None = None,
        hook_config_hash="",
        mcp_config_hash="",
        plans=None,
        storage=None,
        memory=None,
        recover_tool=None,
        recovery_outputs=None,
    ) -> None:
        self._store = store
        self._projector = projector
        self._models = models
        self._launcher = launcher
        self._default = default
        self._execution = asyncio.Lock()
        self._active_task: asyncio.Task | None = None
        self._durable_inputs = durable_inputs
        self._submission = asyncio.Lock()
        self._revert = revert
        self._view = view
        self._skills = skills
        self._hook_config_hash = hook_config_hash
        self._mcp_config_hash = mcp_config_hash
        self._plans = plans
        self._storage = storage
        self._memory = memory
        self._recover_tool = recover_tool
        self._recovery_outputs = recovery_outputs

    async def submit_input(self, message: Message, kind: InputKind) -> bool:
        if self._durable_inputs is None:
            raise StorageError("queue_unavailable", "This session has no durable input queue")
        async with self._submission:
            snapshot = await self._store.snapshot()
            if pending_revert(snapshot) is not None:
                raise StorageError(
                    "revert_recovery_required", "Recover the pending revert before queueing input"
                )
            start = self.pending_run(snapshot)
            if start is None:
                for entry in snapshot.entries:
                    if entry.type == "input_queued" and entry.message.id == message.id:
                        return await self._durable_inputs.submit(message, kind, entry.run_id)
                raise StorageError("no_pending_run", "Start a task before queueing input")
            return await self._durable_inputs.submit(message, kind, start.run_id)

    @asynccontextmanager
    async def execution(self, *, allow_revert_recovery=False):
        if self._execution.locked():
            raise StorageError("session_busy", "This session already has an active executor")
        async with self._execution:
            self._active_task = asyncio.current_task()
            try:
                if (
                    not allow_revert_recovery
                    and pending_revert(await self._store.snapshot()) is not None
                ):
                    raise StorageError(
                        "revert_recovery_required",
                        "Recover the pending revert before executing commands",
                    )
                yield
            finally:
                self._active_task = None

    async def cancel(self) -> bool:
        running = self._active_task
        if running is None:
            async with self.execution():
                start = self.pending_run(await self._store.snapshot())
                if start is None:
                    return False
                outputs = await self._launcher.cancel_wait(start.run_id)
                await recover_conversation(
                    self._store,
                    outputs or self._recovery_outputs,
                    reason="user_cancel",
                    outcome="cancelled",
                    recover_tool=self._recover_tool,
                )
                if self._plans is not None:
                    await self._plans.finish(start.run_id)
                return True
        if running is asyncio.current_task():
            raise StorageError("invalid_cancel_owner", "Cancellation must come from another task")
        running.cancel()
        try:
            await running
        except asyncio.CancelledError:
            pass
        return True

    async def select_model(self, provider: str, model_id: str):
        if self._execution.locked():
            raise ModelError(
                "session_busy", "Wait for the current run to settle before switching models"
            )
        async with self.execution():
            if self.pending_run(await self._store.snapshot()) is not None:
                raise StorageError(
                    "session_needs_input", "Resolve the pending run before switching models"
                )
            return await self._models.select(provider, model_id)

    async def model_list(self, *, refresh=False):
        return await self._models.list_models(refresh=refresh)

    async def name(self, title):
        async with self.execution():
            return await name_session(self._store, title)

    async def initialize_memory(self):
        if self._memory is not None:
            await self._memory.initialize()

    async def configure_fork_context(self, source_entry_ids):
        async with self.execution():
            self.require_settled(await self._store.snapshot())
            await self._store.append_entry(
                EntryDraft(
                    "fork_context",
                    data={"mode": "recent", "source_entry_ids": list(source_entry_ids)},
                )
            )

    async def load_skill(self, name, args=""):
        if self._skills is None:
            raise StorageError("skills_unavailable", "This session has no Skill service")
        selected = await self._skills.request(name, args)
        prompt = (
            f"Use the {selected['name']} Skill. Read {selected['path']} for its instructions.\n"
            f"Task arguments: {selected['arguments']}"
        )
        if selected["mode"] == "fork":
            prompt = (
                "Delegate this Skill using the task tool with subagent_type="
                + "skill-"
                + selected["name"]
                + ".\n"
                + prompt
            )
        return await self.run(
            Message(uuid4().hex, "user", prompt, provider_metadata={"skill_request": selected})
        )

    async def reload_skills(self):
        async with self.execution():
            self.require_settled(await self._store.snapshot())
            if self._skills is None:
                raise StorageError("skills_unavailable", "This session has no Skill service")
            return await self._skills.refresh()

    async def skill_search(self, query="", limit=10):
        if self._skills is None:
            raise StorageError("skills_unavailable", "This session has no Skill service")
        return await self._skills.search(query, limit)

    async def install_skill(self, url, ref=None, subpath=None):
        async with self.execution():
            self.require_settled(await self._store.snapshot())
            if self._skills is None:
                raise StorageError("skills_unavailable", "This session has no Skill service")
            return await self._skills.install(url, ref=ref, subpath=subpath)

    async def branch(self, leaf_id: str | None) -> None:
        if self._execution.locked():
            raise StorageError(
                "session_busy", "Wait for the current run to settle before changing branches"
            )
        async with self.execution():
            if self.pending_run(await self._store.snapshot()) is not None:
                raise StorageError(
                    "session_needs_input", "Resolve the pending run before changing branches"
                )
            await self._store.branch(leaf_id)
            if self._plans is not None:
                await self._plans.initialize()

    async def enter_plan(self, task=""):
        async with self.execution():
            self.require_settled(await self._store.snapshot())
            if self._plans is None:
                raise StorageError("plan_unavailable", "This session has no Plan service")
            state = await self._plans.enter()
            if not task:
                await self._store.append_message(Message(uuid4().hex, "user", "/plan"))
                return state
        return await self.run(Message(uuid4().hex, "user", "Create a Plan for: " + task))

    async def storage_status(self):
        if self._storage is None:
            raise StorageError("storage_unavailable", "This session has no object service")
        return await self._storage.status()

    async def storage_gc(self, *, dry_run=False):
        async with self.execution():
            self.require_settled(await self._store.snapshot())
            if self._storage is None:
                raise StorageError("storage_unavailable", "This session has no object service")
            return await self._storage.collect(dry_run=dry_run)

    async def run(self, message: Message) -> RunResult:
        if message.role != "user" or not message.id:
            raise ValueError("A task starts with an identified user input")
        if self._execution.locked():
            raise StorageError("session_busy", "This session already has an active executor")
        async with self.execution():
            before = await self._store.snapshot()
            path = SessionTree(before.index).path(before.leaf_id)
            pending = self.pending_run(before)
            original = next(
                (
                    entry
                    for entry in before.entries
                    if entry.type == "message" and entry.message.id == message.id
                ),
                None,
            )
            if original is not None:
                if original.message != message:
                    raise StorageError(
                        "input_id_conflict", "Input ID already has different content"
                    )
                if original.id not in {entry.id for entry in path}:
                    raise StorageError(
                        "input_branch_conflict", "Input ID belongs to another branch"
                    )
                for entry in reversed(path):
                    if (
                        entry.type == "custom"
                        and entry.data.get("kind") == "run_end"
                        and (
                            entry.data.get("input_id") == message.id
                            or message.id in entry.data.get("input_ids", ())
                        )
                    ):
                        final_id = entry.data.get("final_entry_id")
                        final_text = (
                            SessionTree(before.index).get(final_id).message.text if final_id else ""
                        )
                        return RunResult(
                            entry.data["outcome"],
                            final_text,
                            (),
                            checkpoint_id=entry.data.get("checkpoint_id"),
                            run_id=entry.run_id,
                        )
                following = path[path.index(original) + 1 :]
                if pending is not None and pending.data["input_id"] == message.id:
                    provider = await self._models.restore(self._default)
                    return await self.resume_run(provider, pending)
                if any(
                    entry.type == "message" and entry.message.role in {"assistant", "tool"}
                    for entry in following
                ):
                    raise StorageError(
                        "execution_recovery_required",
                        "Unsettled execution must be reconciled before continuing",
                    )
                history = tuple(
                    item
                    for item in self._projector.project(before).messages
                    if item.id != message.id
                )
            else:
                if pending is not None:
                    raise StorageError(
                        "session_needs_input",
                        "Resume or resolve the pending task before adding input",
                    )
                history = self._projector.project(before).messages
                await self._store.append_message(message)
                if self._memory is not None:
                    await self._memory.select(message.text)
                if await self._models.current() is None:
                    await self._models.select(self._default.name, self._default.model_id)
            if self._skills is not None:
                await self._skills.index()
            provider = await self._models.restore(self._default)
            start = await self._store.append_entry(
                EntryDraft(
                    "custom",
                    data={
                        "kind": "run_start",
                        "input_id": message.id,
                        "execution_session_id": before.header.session_id,
                        "hook_config_hash": self._hook_config_hash,
                        "mcp_config_hash": self._mcp_config_hash,
                    },
                    run_id=uuid4().hex,
                )
            )
            try:
                result = await self._launcher.run(provider, self._store, (message,), history)
            except asyncio.CancelledError:
                await recover_conversation(
                    self._store, self._recovery_outputs, recover_tool=self._recover_tool
                )
                raise
            except DomainError:
                try:
                    await recover_conversation(
                        self._store,
                        self._recovery_outputs,
                        reason="run_error",
                        outcome="error",
                        recover_tool=self._recover_tool,
                    )
                except StorageError:
                    logging.getLogger("pycode.session").exception(
                        "Could not persist failed-run recovery; "
                        "restart requires JSONL reconciliation"
                    )
                raise
            return await self.settle_run(start, result)

    def pending_run(self, snapshot):
        return pending_run_entry(snapshot)

    async def resume(self, answers: dict | None = None) -> RunResult:
        if self._execution.locked():
            raise StorageError("session_busy", "This session already has an active executor")
        async with self.execution():
            start = self.pending_run(await self._store.snapshot())
            if start is None:
                raise StorageError("no_pending_run", "This branch has no pending execution")
            provider = await self._models.restore(self._default)
            return await self.resume_run(provider, start, answers)

    async def resume_run(self, provider, start, answers=None):
        # Take the durable writer lease before observing or resuming graph execution.
        await self._store.append_entry(
            EntryDraft(
                "custom",
                data={"kind": "run_resume", "input_id": start.data["input_id"]},
                run_id=start.run_id,
            )
        )
        try:
            result = await self._launcher.resume(provider, self._store, start.run_id, answers)
        except asyncio.CancelledError:
            await recover_conversation(
                self._store, self._recovery_outputs, recover_tool=self._recover_tool
            )
            raise
        return await self.settle_run(start, result)

    async def settle_run(self, start, result):
        async with self._submission:
            return await self.record_run_result(start, result)

    async def record_run_result(self, start, result):
        result = replace(result, run_id=start.run_id)
        if result.outcome == "needs_input":
            await self._store.append_entry(
                EntryDraft(
                    "custom",
                    data={
                        "kind": "run_wait",
                        "pending": list(result.pending),
                        "checkpoint_id": result.checkpoint_id,
                    },
                    run_id=start.run_id,
                )
            )
            return result
        message_id = start.data["input_id"]
        after = await self._store.snapshot()
        path = SessionTree(after.index).path(after.leaf_id)
        input_ids = [message_id]
        input_ids.extend(
            entry.message.id
            for entry in path
            if entry.type == "input_queued" and entry.run_id == start.run_id
        )
        final_id = None
        for entry in reversed(SessionTree(after.index).path(after.leaf_id)):
            if entry.type == "message" and entry.message.id == message_id:
                break
            if entry.type == "message" and entry.message.role == "assistant":
                final_id = entry.id
                break
        await self._store.append_entry(
            EntryDraft(
                "custom",
                data={
                    "kind": "run_end",
                    "input_id": message_id,
                    "input_ids": input_ids,
                    "checkpoint_id": result.checkpoint_id,
                    "outcome": result.outcome,
                    "final_entry_id": final_id,
                },
                run_id=start.run_id,
            )
        )
        if self._plans is not None:
            await self._plans.finish(start.run_id)
        if self._memory is not None and result.outcome == "completed":
            await self._memory.settled(start.run_id)
        return result

    async def aclose(self) -> None:
        if self._execution.locked():
            raise StorageError("session_busy", "Cancel and collect the active run before closing")
        try:
            try:
                if self._memory is not None:
                    await self._memory.aclose()
            finally:
                await self._launcher.aclose()
        finally:
            try:
                if self._view is not None:
                    await self._view.aclose()
            finally:
                await self._store.aclose()

    async def inspect(self, *, presentation=False):
        if self._view is None:
            raise StorageError("view_unavailable", "This session has no view service")
        state = await self._view.inspect(presentation=presentation)
        state["plan"] = (
            plain_data(self._plans.state)
            if self._plans is not None and self._plans.state is not None
            else None
        )
        return state

    async def usage(self, run_id=None):
        if self._view is None:
            raise StorageError("usage_unavailable", "This session has no usage service")
        return await self._view.usage(run_id)

    async def request_records(self):
        if self._view is None:
            raise StorageError("view_unavailable", "This session has no request view")
        return await self._view.request_records()

    async def read_tool_result(self, output_id, *, offset=1, limit=2000, byte_offset=0):
        if self._view is None:
            raise StorageError("view_unavailable", "This session has no view service")
        return await self._view.read_tool_result(
            output_id, offset=offset, limit=limit, byte_offset=byte_offset
        )

    async def memory_list(self):
        if self._memory is None:
            raise StorageError("memory_unavailable", "This session has no memory service")
        return await self._memory.list()

    async def collect_memory(self):
        if self._memory is not None:
            await self._memory.collect()

    async def memory_mode(self, mode):
        if self._memory is None:
            raise StorageError("memory_unavailable", "This session has no memory service")
        await self._memory.set_mode(mode)

    async def memory_remove(self, memory_id, *, scope=None):
        async with self.execution():
            if self._memory is None:
                raise StorageError("memory_unavailable", "This session has no memory service")
            await self._memory.remove(memory_id, scope=scope)

    async def memory_edit(self, memory_id, content, *, scope=None):
        async with self.execution():
            if self._memory is None:
                raise StorageError("memory_unavailable", "This session has no memory service")
            return await self._memory.edit(memory_id, content, scope=scope)

    async def memory_undo(self):
        async with self.execution():
            if self._memory is None:
                raise StorageError("memory_unavailable", "This session has no memory service")
            return await self._memory.undo()

    async def memory_clear(self):
        async with self.execution():
            if self._memory is None:
                raise StorageError("memory_unavailable", "This session has no memory service")
            await self._memory.clear()

    async def memory_extract(self):
        async with self.execution():
            self.require_settled(await self._store.snapshot())
            if self._memory is None or self._memory.updater is None:
                raise StorageError("memory_unavailable", "No memory updater is configured")
            change_id = await self._memory.consolidate("manual-memory-" + uuid4().hex, "manual")
            return {"change_id": change_id}

    async def publish_result(self, result, *, collect_background=False):
        if self._view is None or result.run_id is None:
            raise StorageError(
                "result_unavailable", "This result has no durable execution identity"
            )
        if collect_background:
            await self._launcher.aclose()
        return await self._view.publish_result(result)

    async def compact(self):
        async with self.execution():
            self.require_settled(await self._store.snapshot())
            provider = await self._models.restore(self._default)
            await self._store.append_entry(EntryDraft("custom", data={"kind": "compact_request"}))
            return await self._launcher.compact(provider, self._store)

    async def reconcile_hook(self, operation_id, action, text="", is_error=False):
        async with self.execution():
            self.require_settled(await self._store.snapshot())
            await self._store.append_entry(
                EntryDraft(
                    "custom",
                    data={
                        "kind": "hook_review_request",
                        "operation_id": operation_id,
                        "action": action,
                    },
                )
            )
            await self._launcher.reconcile_hook(self._store, operation_id, action, text, is_error)

    async def preview_revert(self, target_leaf_id, mode="dialogue"):
        async with self.execution():
            self.require_settled(await self._store.snapshot())
            if self._revert is None:
                raise StorageError("revert_unavailable", "This session has no revert service")
            return await self._revert.preview(target_leaf_id, mode)

    async def revert(self, plan):
        async with self.execution():
            self.require_settled(await self._store.snapshot())
            if self._revert is None:
                raise StorageError("revert_unavailable", "This session has no revert service")
            result = await self._revert.apply(plan)
            await self._launcher.invalidate_summary("reverted")
            if self._plans is not None:
                await self._plans.initialize()
            return result

    def require_settled(self, snapshot):
        if self.pending_run(snapshot) is not None:
            raise StorageError(
                "session_needs_input", "Resolve the pending run before changing conversation state"
            )

    async def recover_revert(self, restore_id, *, compensate=False):
        async with self.execution(allow_revert_recovery=True):
            if self._revert is None:
                raise StorageError("revert_unavailable", "This session has no revert service")
            result = await self._revert.recover(restore_id, compensate=compensate)
            await self._launcher.invalidate_summary("reverted")
            if self._plans is not None:
                await self._plans.initialize()
            return result
