"""The application memory lifecycle owns JSON recall, staged jobs and automatic commits."""

import asyncio
import contextvars
from dataclasses import replace
from uuid import uuid4

from pycode.core.errors import ModelError, StorageError, ToolError
from pycode.core.memory import snapshot_record
from pycode.core.memory_evidence import (
    MemoryEvidenceDecision,
    MemoryEvidencePolicy,
    MemoryWritePermit,
)
from pycode.core.memory_jobs import REORGANIZE_ERRORS, memory_job
from pycode.core.memory_schema import resolve_memory_key, validate_memory_value
from pycode.core.memory_tool_receipt import receipt_id
from pycode.core.messages import Message, ToolCall, ToolResult
from pycode.core.session import EntryDraft
from pycode.core.session_tree import SessionTree
from pycode.core.summary_source import data_hash
from pycode.core.values import plain_data
from pycode.services.memory_commit import MemoryCommitService, observed_targets
from pycode.services.memory_evidence import capture_memory_evidence


class JsonMemoryService:
    def __init__(
        self,
        history,
        store,
        scopes,
        timing,
        jobs,
        sources,
        migration,
        *,
        updater=None,
        mode=None,
        moves=None,
    ):
        self.history, self.store, self.scopes, self.timing = history, store, scopes, timing
        self.jobs, self.sources, self.migration, self.updater = jobs, sources, migration, updater
        self.mode = mode
        self.moves = moves
        self.user_namespace = next(ns for ns, scope in scopes.items() if scope == "user")
        self.tasks = {}
        self.lock = asyncio.Lock()
        self.migrated = False
        self.closed = False

    async def ensure_store(self):
        if not self.migrated:
            await self.migration.run()
            self.migrated = True

    async def initialize(self):
        if self.mode is None:
            self.mode = await self.jobs.get_mode(self.user_namespace)
            if self.mode == "review":
                self.mode = "auto"
                await self.jobs.set_mode(self.user_namespace, "auto")
        if self.mode not in {"auto", "off"}:
            raise StorageError("invalid_memory_mode", "Choose auto or off")
        await self.recover_moves()
        if self.mode == "off":
            return
        await self.ensure_store()
        if self.updater is not None:
            snapshot = await self.history.snapshot()
            for namespace in self.scopes:
                pending = await self.jobs.pending(
                    namespace, snapshot.header.session_id, snapshot.header.workspace
                )
                if pending:
                    await self.settled("memory-recovery-" + uuid4().hex)
                    break

    async def set_mode(self, mode):
        if mode not in {"auto", "off"}:
            raise StorageError("invalid_memory_mode", "Choose auto or off")
        if mode == "off":
            await self.collect(cancel=True)
        await self.jobs.set_mode(self.user_namespace, mode)
        self.mode = mode
        if mode == "off":
            await self.history.append_entry(
                EntryDraft("memory_load", data={"records": [], "loaded_at": self.timing.now()})
            )
        else:
            await self.ensure_store()
            await self.settled("memory-enabled-" + uuid4().hex)

    async def list(self):
        await self.ensure_store()
        records = []
        for namespace, scope in self.scopes.items():
            snapshot = await self.store.snapshot(namespace)
            for key, record in snapshot["records"].items():
                records.append(snapshot_record(namespace, key, record, scope))
        return {"mode": self.mode, "records": plain_data(records), "candidates": []}

    async def select(self, query):
        if self.mode == "off":
            return ()
        await self.ensure_store()
        snapshot = await self.history.snapshot()
        records = ()
        try:
            if self.updater is None:
                raise ModelError("memory_model_required", "Recall requires a configured provider")
            records = await self.updater.recall(
                self.store,
                self.scopes,
                snapshot.header.session_id,
                "memory-recall-" + uuid4().hex,
                query,
            )
        except (ModelError, ToolError) as error:
            await self.history.append_entry(
                EntryDraft("memory_recall_error", data={"reason": error.code})
            )
        await self.history.append_entry(
            EntryDraft(
                "memory_load",
                data={"records": plain_data(records), "loaded_at": self.timing.now()},
            )
        )
        return records

    async def settled(self, run_id):
        if self.closed or self.mode == "off" or self.updater is None or run_id in self.tasks:
            return
        self.tasks[run_id] = asyncio.create_task(
            self.extract_snapshot(run_id),
            name="pycode-memory-update",
            context=contextvars.Context(),
        )

    async def audit_completed(self, job):
        current = await self.history.snapshot()
        if not any(
            entry.type == "memory_extract"
            and entry.data.get("job_id") == job.job_id
            and entry.data.get("status") == "completed"
            for entry in current.entries
        ):
            await self.history.append_entry(
                EntryDraft(
                    "memory_extract",
                    run_id=job.run_id,
                    data={
                        "job_id": job.job_id,
                        "status": "completed",
                        "source_leaf_id": job.source_leaf_id,
                        "source_entry_ids": job.entry_ids,
                        "namespace": list(job.namespace),
                    },
                )
            )
        if job.status != "completed":
            await self.jobs.update(job, now=self.timing.now(), status="completed", error_code=None)

    async def process(self, job):
        try:
            return await self.process_current(job)
        except asyncio.CancelledError:
            latest = await self.jobs.get(job.namespace, job.job_id)
            if latest is not None and latest.status not in {"completed", "obsolete"}:
                await self.jobs.update(
                    latest, now=self.timing.now(), status="pending_retry", error_code="shutdown"
                )
            raise
        except (ToolError, ModelError, StorageError) as error:
            latest = await self.jobs.get(job.namespace, job.job_id)
            if latest is not None and latest.status not in {"completed", "obsolete"}:
                await self.jobs.update(
                    latest,
                    now=self.timing.now(),
                    status="pending_retry",
                    error_code=error.code,
                    reorganize_reason=(
                        error.code if error.code in REORGANIZE_ERRORS else latest.reorganize_reason
                    ),
                )
            if isinstance(error, StorageError) and error.code not in {
                "memory_revision_conflict",
                "memory_source_changed",
                "memory_target_conflict",
                "memory_rule_evaluation_target_changed",
            }:
                raise
            return False

    async def process_current(self, job):
        current = await self.history.snapshot()
        path = SessionTree(current.index).path(current.leaf_id)
        if job.source_leaf_id not in {entry.id for entry in path}:
            await self.jobs.update(
                job, now=self.timing.now(), status="obsolete", error_code="memory_source_changed"
            )
            return True
        source = replace(current, leaf_id=job.source_leaf_id)
        source_path = SessionTree(source.index).path(source.leaf_id)
        if data_hash([plain_data(entry) for entry in source_path]) != job.source_prefix_hash:
            raise StorageError("memory_source_changed", "Memory job source changed")
        state = await self.store.snapshot(job.namespace)
        saved = state["processed_batches"].get(job.job_id)
        if saved is not None:
            if (
                saved["session_id"] != job.session_id
                or saved["branch_id"] != job.source_leaf_id
                or saved["prefix_hash"] != job.source_prefix_hash
                or saved["entry_ids"] != job.entry_ids
                or saved["start"] != job.start.model_dump(mode="json")
                or saved["end"] != job.end.model_dump(mode="json")
                or saved.get("end_output_positions", {})
                != job.model_dump(mode="json")["end_output_positions"]
            ):
                raise StorageError(
                    "memory_batch_conflict", "Saved receipt belongs to another source"
                )
            await self.audit_completed(job)
            return True
        retry_reason = job.reorganize_reason or (
            job.error_code if job.error_code in REORGANIZE_ERRORS else None
        )
        if job.status != "running" or retry_reason != job.reorganize_reason:
            job = await self.jobs.update(
                job,
                now=self.timing.now(),
                status="running",
                error_code=None,
                reorganize_reason=retry_reason,
            )
        proposals = job.model_dump(mode="json")["proposals"]
        retry_proposals = proposals if retry_reason is not None else None
        partial = any(
            receipt["batch_id"] == job.job_id for receipt in state["group_receipts"].values()
        )
        if retry_proposals is not None or (
            job.observed_revision != state["revision"] and not partial
        ):
            proposals = None
        targets = (
            job.model_dump(mode="json")["observed_targets"]
            if partial and retry_proposals is None
            else None
        )

        async def checkpoint(prepared, evaluations):
            nonlocal job
            changes = plain_data(prepared.changes[job.namespace])
            previous = plain_data(prepared.previous[job.namespace])
            job = await self.jobs.update(
                job,
                now=self.timing.now(),
                status="running",
                proposals=changes,
                observed_revision=prepared.revisions[job.namespace],
                observed_targets=observed_targets(changes, previous),
                evaluations=evaluations,
                reorganize_reason=None,
            )

        async def persist(prepared):
            nonlocal job
            if proposals is not None and not job.evaluations:
                job = await self.jobs.update(job, now=self.timing.now(), status="proposed")
                return
            changes = plain_data(prepared.changes[job.namespace])
            changes.update(plain_data(prepared.retained_proposals.get(job.namespace, {})))
            previous = plain_data(prepared.previous[job.namespace])
            job = await self.jobs.update(
                job,
                now=self.timing.now(),
                status="proposed",
                proposals=changes,
                observed_revision=prepared.revisions[job.namespace],
                observed_targets=observed_targets(changes, previous),
                evaluations=plain_data(prepared.evaluations),
                reorganize_reason=None,
            )

        result = await self.updater.update(
            self.history,
            self.store,
            self.scopes,
            job.namespace,
            source,
            job.job_id,
            output_reader=self.sources.output_reader,
            entry_ids=job.entry_ids,
            ranges={key: value for key, value in job.ranges.items() if value[0] != value[1]},
            batch_id=job.job_id,
            progress={
                "session_id": job.session_id,
                "branch_id": job.source_leaf_id,
                "entry_ids": job.entry_ids,
                "prefix_hash": job.source_prefix_hash,
                "start": job.start.model_dump(mode="json"),
                "end": job.end.model_dump(mode="json"),
                "end_output_positions": job.model_dump(mode="json")["end_output_positions"],
            },
            before_save=persist,
            proposals=proposals,
            observed_revision=job.observed_revision,
            expected_targets=targets,
            output_positions=job.model_dump(mode="json")["output_positions"],
            retry_proposals=retry_proposals,
            retry_reason=retry_reason,
            evaluations=job.model_dump(mode="json")["evaluations"],
            evaluation_checkpoint=checkpoint,
        )
        if not result.can_advance:
            reason = (
                result.rejected_groups[0]["reason"]
                if result.rejected_groups
                else "memory_update_rejected"
            )
            await self.jobs.update(
                job,
                now=self.timing.now(),
                status="pending_retry",
                error_code=reason,
                reorganize_reason=result.reorganize_reason
                or next(
                    (
                        group["reason"]
                        for group in result.rejected_groups
                        if group["reason"] in REORGANIZE_ERRORS
                    ),
                    None,
                ),
            )
            return False
        await self.audit_completed(job)
        return True

    async def extract_snapshot(self, run_id):
        if self.mode == "off" or self.updater is None:
            return
        await self.ensure_store()
        async with self.lock:
            current = await self.history.snapshot()
            for namespace in self.scopes:
                pending = await self.jobs.pending(
                    namespace, current.header.session_id, current.header.workspace
                )
                failed = False
                for job in pending:
                    if not await self.process(job):
                        failed = True
                        break
                if failed:
                    continue
                while not self.closed and self.mode != "off":
                    current = await self.history.snapshot()
                    state = await self.store.snapshot(namespace)
                    window = await self.sources.next(current, state["processed_batches"])
                    if window is None:
                        break
                    job = await self.jobs.create(
                        memory_job(
                            namespace,
                            current,
                            window.entry_ids,
                            window.ranges,
                            run_id,
                            self.timing.now(),
                            output_positions=window.output_positions,
                            end_output_positions=window.end_output_positions,
                        )
                    )
                    if job.status in {"completed", "obsolete"}:
                        raise StorageError(
                            "memory_job_receipt_missing", "Closed job has no matching progress"
                        )
                    if not await self.process(job):
                        break

    async def collect(self, *, cancel=False):
        tasks = tuple(self.tasks.values())
        if cancel:
            for task in tasks:
                if not task.done():
                    task.cancel()
        results = await asyncio.gather(*tasks, return_exceptions=True)
        self.tasks.clear()
        for result in results:
            if isinstance(result, BaseException) and not isinstance(result, asyncio.CancelledError):
                raise result

    async def aclose(self):
        self.closed = True
        await self.collect(cancel=True)

    async def target(self, memory_id, scope=None):
        await self.ensure_store()
        found = []
        for namespace, bound_scope in self.scopes.items():
            if scope is not None and scope != bound_scope:
                continue
            snapshot = await self.store.snapshot(namespace)
            key = resolve_memory_key(snapshot["records"], memory_id)
            if key in snapshot["records"]:
                found.append((namespace, key, snapshot["records"][key]))
        if len(found) != 1:
            raise ToolError("memory_target_ambiguous", "Choose one existing scoped memory")
        return found[0]

    async def command(self, namespace, changes, previous, text):
        entry = await self.history.append_entry(
            EntryDraft(
                "memory_edit",
                message=Message(uuid4().hex, "user", text),
                data={"namespace": list(namespace), "memory_ids": list(changes)},
            )
        )
        snapshot = await self.history.snapshot()
        for key, value in changes.items():
            if value is not None:
                updated = plain_data(value)
                body = updated["content"]["body"]
                if body not in text:
                    raise ToolError(
                        "memory_edit_rejected", "Editor content is outside its user action"
                    )
                updated["content"]["sources"].append(
                    {
                        "session_id": snapshot.header.session_id,
                        "entry_id": entry.id,
                        "quote": body,
                    }
                )
                changes[key] = validate_memory_value(updated)
        evidence = await capture_memory_evidence(snapshot, entry_ids=(entry.id,))
        permit = MemoryWritePermit()
        view = await self.store.open_update(validate_change=permit)
        try:
            view.write_changes(namespace, changes)
            policy = MemoryEvidencePolicy(evidence, self.scopes)

            async def explicit(scope, proposals, sources):
                supported = scope == self.scopes[namespace] and len(proposals) == len(changes)
                for proposal in proposals:
                    key = proposal["key"]
                    supported = (
                        supported
                        and key in changes
                        and data_hash(proposal["value"]) == data_hash(changes.get(key))
                    )
                    if data_hash(proposal["previous"]) != data_hash(previous.get(key)):
                        raise StorageError(
                            "memory_revision_conflict", "Memory changed before explicit edit"
                        )
                return MemoryEvidenceDecision(
                    supported,
                    True,
                    supported,
                    deletion_requested=all(value is None for value in changes.values()),
                )

            coordinator = MemoryCommitService(self.history, policy, permit, explicit)
            deletion_sources = [
                {"session_id": snapshot.header.session_id, "entry_id": entry.id, "quote": text}
            ]
            result = await coordinator.commit(
                view, namespace, delete_sources={key: deletion_sources for key in changes}
            )
            if not result.can_advance:
                raise ToolError("memory_edit_rejected", "Explicit edit failed evidence validation")
            await self.history.append_entry(
                EntryDraft(
                    "memory_applied",
                    data={"memory_ids": list(result.accepted_ids), "namespace": list(namespace)},
                )
            )
            return result.accepted_ids
        finally:
            view.discard()

    async def edit(self, memory_id, content, *, scope=None, from_scope=None):
        namespace, key, previous = await self.target(memory_id, from_scope)
        if scope is not None and scope != self.scopes[namespace]:
            return await self.move(
                memory_id, scope, content=content, from_scope=self.scopes[namespace]
            )
        value = plain_data(previous["value"])
        value["content"].update(body=content, summary=content[:160])
        if value["kind"] == "ProceduralMemory":
            value["content"]["origin"] = "user_explicit"
        elif value["kind"] == "EpisodicMemory":
            value["content"]["verification"] = "unverified"
        # Validate user content before recording the explicit editor action.
        value = validate_memory_value(value)
        text = "Update saved memory " + key + " permanently:\n" + content
        await self.command(namespace, {key: value}, {key: previous}, text)
        return key

    async def finish_move(self, move):
        from pycode.core.memory_move import MemoryMove
        from pycode.core.values import json_dumps

        move = MemoryMove.model_validate_json(json_dumps(move.model_dump(mode="json")))
        if move.source_namespace not in self.scopes or move.target_namespace not in self.scopes:
            raise StorageError("memory_scope_denied", "Move belongs to another configured project")
        snapshot = await self.history.snapshot()
        path = SessionTree(snapshot.index).path(snapshot.leaf_id)
        position = next(
            (i for i, entry in enumerate(path) if entry.id == move.source_leaf_id), None
        )
        action = next((entry for entry in path if entry.id == move.action_entry_id), None)
        expected_text = (
            f"Move saved memory {move.key} from {self.scopes[move.source_namespace]} "
            f"to {self.scopes[move.target_namespace]} permanently:\n"
            + (move.content if move.content is not None else move.record.value.content.body)
        )
        if (
            snapshot.header.session_id != move.session_id
            or snapshot.header.workspace != move.workspace
            or position is None
            or data_hash([plain_data(e) for e in path[: position + 1]]) != move.source_prefix_hash
        ):
            raise StorageError(
                "memory_move_source_changed", "Move source is no longer on this branch"
            )
        if (
            action is None
            or action.type != "memory_edit"
            or action.message is None
            or action.message.role != "user"
            or action.message.text != expected_text
            or action.data.get("source_namespace") != tuple(move.source_namespace)
            or action.data.get("target_namespace") != tuple(move.target_namespace)
            or tuple(action.data.get("memory_ids", ())) != (move.key,)
        ):
            raise StorageError(
                "memory_move_source_changed", "Move has no matching explicit user action"
            )
        result = await self.moves.execute(move)
        if result.status == "conflict":
            raise StorageError(
                result.error_code, "Move conflict; source and saved target copies are preserved"
            )
        current = await self.history.snapshot()
        if not any(
            e.type == "memory_move_applied" and e.data.get("move_id") == move.move_id
            for e in current.entries
        ):
            await self.history.append_entry(
                EntryDraft(
                    "memory_move_applied",
                    data={
                        "move_id": move.move_id,
                        "memory_id": move.key,
                        "source_namespace": list(move.source_namespace),
                        "target_namespace": list(move.target_namespace),
                    },
                )
            )
        return move.key

    async def recover_moves(self):
        if self.moves is None:
            return
        snapshot = await self.history.snapshot()
        audited = {e.data["move_id"] for e in snapshot.entries if e.type == "memory_move_applied"}
        pending = await self.moves.pending(snapshot.header.session_id, snapshot.header.workspace)
        for move in pending:
            if move.status == "completed" and move.move_id in audited:
                continue
            await self.ensure_store()
            await self.finish_move(move)

    async def move(self, memory_id, scope, *, content=None, from_scope=None):
        from pycode.core.memory_move import MemoryMove, moved_value
        from pycode.core.values import json_dumps

        if self.moves is None or scope not in {"user", "project"}:
            raise ToolError("invalid_memory_scope", "Choose user or project memory")
        namespace, key, previous = await self.target(memory_id, from_scope)
        destination = next(ns for ns, chosen in self.scopes.items() if chosen == scope)
        if destination == namespace:
            if content is not None:
                return await self.edit(key, content)
            return key
        body = content if content is not None else previous["value"]["content"]["body"]
        entry = await self.history.append_entry(
            EntryDraft(
                "memory_edit",
                message=Message(
                    uuid4().hex,
                    "user",
                    f"Move saved memory {key} from {self.scopes[namespace]} "
                    f"to {scope} permanently:\n" + body,
                ),
                data={
                    "source_namespace": list(namespace),
                    "target_namespace": list(destination),
                    "memory_ids": [key],
                },
            )
        )
        snapshot = await self.history.snapshot()
        path = SessionTree(snapshot.index).path(snapshot.leaf_id)
        identity = {
            "source_namespace": list(namespace),
            "target_namespace": list(destination),
            "key": key,
            "record": previous,
            "content": content,
            "session_id": snapshot.header.session_id,
            "workspace": snapshot.header.workspace,
            "source_leaf_id": snapshot.leaf_id,
            "source_prefix_hash": data_hash([plain_data(e) for e in path]),
            "action_entry_id": entry.id,
        }
        move = MemoryMove.model_validate_json(
            json_dumps({**identity, "move_id": data_hash(identity)})
        )
        moved_value(move, scope)
        return await self.finish_move(move)

    async def remove(self, memory_id):
        namespace, key, previous = await self.target(memory_id)
        await self.command(
            namespace, {key: None}, {key: previous}, "Forget saved memory " + key + " permanently."
        )

    async def clear(self):
        await self.ensure_store()
        for namespace in self.scopes:
            snapshot = await self.store.snapshot(namespace)
            if snapshot["records"]:
                await self.command(
                    namespace,
                    {key: None for key in snapshot["records"]},
                    snapshot["records"],
                    "Forget all saved memories in the "
                    + self.scopes[namespace]
                    + " scope permanently.",
                )

    async def active_operation(self, context, namespace, arguments, *, manage, name):
        if self.closed or self.mode == "off" or self.updater is None or context.agent_id != "lead":
            raise ToolError("memory_disabled", "Memory tools are disabled for this execution")
        if namespace not in self.scopes:
            raise ToolError("memory_scope_denied", "Memory tool namespace is outside this session")
        await self.ensure_store()
        snapshot = await self.history.snapshot()
        arguments_sha256 = data_hash(arguments)
        if manage:
            restored = await self.recover_active_operation(
                context, ToolCall(context.call_id, name, arguments), snapshot
            )
            if restored is not None:
                return restored
        if (
            context.session_id != snapshot.header.session_id
            or context.workspace != snapshot.header.workspace
        ):
            raise ToolError("memory_scope_denied", "Memory tool belongs to another session")
        path = SessionTree(snapshot.index).path(snapshot.leaf_id)
        start = next(
            (
                entry
                for entry in reversed(path)
                if entry.run_id == context.run_id and entry.data.get("kind") == "run_start"
            ),
            None,
        )
        if start is None:
            raise ToolError(
                "memory_task_missing", "Memory tools require the current live user task"
            )
        user = next(
            (
                entry
                for entry in reversed(path[: path.index(start)])
                if entry.message is not None
                and entry.message.role == "user"
                and entry.message.id == start.data["input_id"]
            ),
            None,
        )
        if user is None:
            raise ToolError("memory_evidence_missing", "Current task has no original user input")
        current_entries = [entry for entry in path[path.index(user) :] if entry.message is not None]
        current_ids = {entry.id for entry in current_entries}
        selected = {user.id}
        arguments = plain_data(arguments)
        if manage and arguments.get("content") is not None:
            content = arguments["content"].get("content", {})
            for source in content.get("sources", []):
                if source.get("session_id") == "current" and source.get("entry_id") == "current":
                    quote = source.get("quote")
                    matches = []
                    for entry in current_entries:
                        message = entry.message
                        text = message.tool_result.text if message.tool_result else message.text
                        if (
                            message.role in {"user", "tool"}
                            and isinstance(quote, str)
                            and quote.strip()
                            and quote in text
                        ):
                            matches.append(entry)
                    if len(matches) != 1:
                        raise ToolError(
                            "memory_evidence_invalid", "Quote must identify one current-task source"
                        )
                    source["session_id"] = snapshot.header.session_id
                    source["entry_id"] = matches[0].id
                if (
                    source.get("session_id") == snapshot.header.session_id
                    and source.get("entry_id") in current_ids
                ):
                    selected.add(source["entry_id"])
        entry_ids = tuple(entry.id for entry in path if entry.id in selected)
        return await self.updater.active_operation(
            self.history,
            self.store,
            self.scopes,
            namespace,
            snapshot,
            context,
            arguments,
            manage=manage,
            name=name,
            entry_ids=entry_ids,
            output_reader=self.sources.output_reader,
            arguments_sha256=arguments_sha256,
        )

    async def recover_active_operation(self, context, call, snapshot=None):
        scope = {"manage_user_memory": "user", "manage_project_memory": "project"}.get(call.name)
        if scope is None or call.parse_error:
            return None
        namespace = next(
            (ns for ns, configured in self.scopes.items() if configured == scope), None
        )
        if namespace is None:
            return None
        saved = await self.store.tool_receipt_snapshot(
            namespace, receipt_id(namespace, context, call.name)
        )
        if saved is None:
            return None
        receipt, records = saved
        snapshot = snapshot or await self.history.snapshot()
        path = SessionTree(snapshot.index).path(snapshot.leaf_id)
        source_index = next(
            (i for i, entry in enumerate(path) if entry.id == receipt["source_leaf_id"]), None
        )
        if (
            receipt["session_id"] != snapshot.header.session_id
            or receipt["workspace"] != snapshot.header.workspace
            or receipt["workspace"] != context.workspace
            or receipt["arguments_sha256"] != data_hash(call.arguments)
            or source_index is None
            or data_hash([plain_data(entry) for entry in path[: source_index + 1]])
            != receipt["source_prefix_hash"]
        ):
            raise StorageError(
                "memory_tool_conflict", "Saved memory call differs from its source or arguments"
            )
        projected, deleted = [], []
        keys = list(receipt["record_versions"])
        for key in keys:
            if key in records:
                projected.append(snapshot_record(namespace, key, records[key], scope))
            else:
                deleted.append(key)
        return ToolResult(
            context.call_id,
            call.name,
            "Saved memory operation: " + ", ".join(keys),
            session_id=context.session_id,
            run_id=context.run_id,
            operation_id=context.operation_id,
            details={
                "memory_namespace": list(namespace),
                "memory_ids": keys,
                "memory_records": plain_data(projected),
                "memory_deleted": deleted,
                "memory_operation_recovered": True,
            },
        )
