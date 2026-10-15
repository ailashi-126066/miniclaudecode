"""In-process child sessions with durable tasks, inboxes and explicit ownership."""

import asyncio
import logging
from uuid import uuid4

from pycode.core.approvals import validate_operation_answer
from pycode.core.errors import DomainError, StorageError, ToolError
from pycode.core.messages import Message
from pycode.core.session import EntryDraft
from pycode.core.team import (
    ACTIVE_AGENT_STATES,
    bounded_text,
    merge_order_error,
    team_message_text,
    valid_record,
    validate_dependencies,
)
from pycode.core.values import plain_data
from pycode.services.team_inbox import TeamInbox
from pycode.services.team_tasks import TeamManager

_LOG = logging.getLogger("pycode.team")


class AgentTeam:
    def __init__(
        self,
        ledger,
        lead_history,
        children,
        worktrees,
        events,
        *,
        max_agents=4,
        plans=None,
        lead_read_only=False,
    ):
        if (
            isinstance(max_agents, bool)
            or not isinstance(max_agents, int)
            or not 1 <= max_agents <= 4
        ):
            raise ValueError("Team concurrency must be 1–4")
        self.ledger, self.lead_history = ledger, lead_history
        self.children, self.worktrees, self.events = children, worktrees, events
        self.max_agents, self.plans, self.lead_read_only = max_agents, plans, lead_read_only
        self.records, self.sessions = {}, {}
        self.lock, self.persist_lock, self.merge_lock = (
            asyncio.Lock(),
            asyncio.Lock(),
            asyncio.Lock(),
        )
        self.changed = asyncio.Event()
        self.board, self.inbox = TeamManager(self.commit), TeamInbox(self)
        self.tasks, self.startups, self.drivers = set(), {}, {}
        self.stopping = self.closing = self.closed = self.degraded = False
        self.epoch = 0
        self._closing_delegate = False
        self.previews = {}
        self.wake_eligible = {}
        self.cancelling = set()

    def require_open(self, *, delegate=False):
        if self.closed or self.degraded or self.stopping or (self.closing and not delegate):
            raise ToolError("team_closed", "Team is stopping, closed or needs storage recovery")

    def require_agent(self, identifier):
        if identifier not in self.records:
            raise ToolError("unknown_agent", "Unknown agent: " + identifier)
        return self.records[identifier]

    def apply(self, data):
        groups = (
            ("agents", self.records, "agent_id"),
            ("tasks", self.board.records, "task_id"),
            ("messages", self.inbox.records, "id"),
        )
        if not isinstance(data, dict) or any(
            not isinstance(data.get(kind, ()), (list, tuple))
            or any(not valid_record(kind, value) for value in data.get(kind, ()))
            for kind, _, _ in groups
        ):
            _LOG.warning("Ignored malformed atomic team transition")
            return
        prospective = {
            **self.board.records,
            **{value["task_id"]: value for value in data.get("tasks", ())},
        }
        try:
            for value in data.get("tasks", ()):
                validate_dependencies(value["task_id"], value["dependencies"], prospective)
        except ToolError:
            _LOG.warning("Ignored team transition with invalid dependencies")
            return
        for kind, target, key in groups:
            for value in data.get(kind, ()):
                target[value[key]] = plain_data(value)

    async def commit(self, *, agents=(), tasks=(), messages=()):
        if not agents and not tasks and not messages:
            return
        data = plain_data(
            {"id": uuid4().hex, "agents": agents, "tasks": tasks, "messages": messages}
        )
        async with self.persist_lock:
            try:
                await self.ledger.append_entry(EntryDraft("team_transition", data=data))
            except BaseException:
                self.degraded = True
                snapshot = await self.ledger.snapshot()
                if any(
                    entry.type == "team_transition" and entry.data.get("id") == data["id"]
                    for entry in snapshot.entries
                ):
                    self.apply(data)
                    self.changed.set()
                else:
                    self.degraded = True
                raise
            self.apply(data)
            self.changed.set()

    async def initialize(self):
        for entry in (await self.ledger.snapshot()).entries:
            if entry.type == "team_transition":
                self.apply(plain_data(entry.data))
        recovery = []
        if self.worktrees is not None:
            for record in self.records.values():
                if record.get("worktree_id"):
                    try:
                        wt = await self.worktrees.inspect(record["worktree_id"])
                        flags = {
                            key: wt.get(key)
                            for key in (
                                "source_recovery_required",
                                "merge_recovery_required",
                                "resolution_pending",
                            )
                        }
                        recovery.append({**record, **flags})
                    except DomainError:
                        recovery.append({**record, "error_code": "worktree_missing"})
        await self.commit(agents=recovery)
        interrupted, tasks, messages = [], [], []
        for record in self.records.values():
            if record["status"] in ACTIVE_AGENT_STATES | {"merging"}:
                record = {**record, "status": "interrupted", "pending": []}
                interrupted.append(record)
                messages.append(
                    self.inbox.notification(
                        record,
                        "agent_interrupted",
                        "Execution interrupted; not resumed",
                        wake=False,
                    )
                )
                tasks.extend(self.board.settled(record["agent_id"], "interrupted", ""))
        for message in self.inbox.records.values():
            if message["status"] == "delivering":
                via = message.get("delivery", {}).get("via")
                owner = message.get("delivery", {}).get("owner")
                accepted = await self.input_accepted(
                    message["recipient"], "team-msg-" + message["id"]
                )
                if via == "wait":
                    accepted = any(
                        e.message is not None
                        and e.message.tool_result is not None
                        and not e.message.tool_result.is_error
                        and e.message.tool_result.name == "wait_agents"
                        and e.message.tool_result.operation_id == owner
                        and message["id"]
                        in e.message.tool_result.details.get("team_message_ids", ())
                        for e in (await self.lead_history.snapshot()).entries
                    )
                if via == "wake":
                    accepted = await self.input_accepted(message["recipient"], owner)
                messages.append({**message, "status": "delivered" if accepted else "pending"})
            recovered = next(
                (item for item in reversed(messages) if item["id"] == message["id"]), message
            )
            if (
                message["recipient"] in {r["agent_id"] for r in interrupted}
                and recovered["status"] == "pending"
            ):
                messages.append({**recovered, "status": "dropped"})
        await self.commit(agents=interrupted, tasks=tasks, messages=messages)

    async def input_accepted(self, recipient, input_id):
        if recipient == "lead":
            snapshot = await self.lead_history.snapshot()
            return any(e.message is not None and e.message.id == input_id for e in snapshot.entries)
        record = self.records.get(recipient)
        return False if record is None else await self.children.input_accepted(record, input_id)

    def member(self, agent_id):
        return TeamMember(self, agent_id)

    def active_count(self):
        return sum(record["status"] in ACTIVE_AGENT_STATES for record in self.records.values())

    def agents_view(self):
        agents = [
            {
                key: record.get(key)
                for key in (
                    "agent_id",
                    "session_id",
                    "kind",
                    "mode",
                    "role",
                    "status",
                    "task_id",
                    "workspace",
                    "branch",
                    "pending",
                    "final_text",
                    "error_code",
                    "runs",
                    "resolution_pending",
                )
            }
            for record in self.records.values()
        ]
        return {"agents": agents, "running": self.active_count(), "limit": self.max_agents}

    async def view(self):
        return {
            **self.agents_view(),
            "tasks": list((await self.board.tasks()).values()),
            "approvals": [
                record for record in self.records.values() if record["status"] == "needs_approval"
            ],
            "degraded": self.degraded,
        }

    async def emit(self, kind, data):
        try:
            await self.events.emit("team", {"type": kind, **data})
        except Exception:
            _LOG.warning("Team event delivery failed", exc_info=True)

    def own(self, coroutine):
        task = asyncio.create_task(coroutine)
        self.tasks.add(task)
        task.add_done_callback(self.background_done)
        return task

    def background_done(self, task):
        self.tasks.discard(task)
        if task.cancelled():
            return
        error = task.exception()
        if error is not None:
            if not (isinstance(error, ToolError) and error.code == "agent_start_failed"):
                self.degraded = True
            self.changed.set()
            _LOG.error(
                "Team background task failed", exc_info=(type(error), error, error.__traceback__)
            )

    async def spawn(
        self,
        mode,
        role,
        task,
        provider,
        *,
        task_id=None,
        run_id=None,
        kind="agent",
        fork_from=None,
        fork_entry_ids=None,
    ):
        role, task = bounded_text(role, "role", 2000), bounded_text(task, "task", 8000)
        if mode not in {"readonly", "worker"}:
            raise ToolError("invalid_agent_mode", "Choose readonly or worker")
        async with self.lock:
            self.require_open(delegate=kind == "delegate" and self._closing_delegate)
            if mode == "worker":
                if self.lead_read_only:
                    raise ToolError("worker_read_only_session", "Readonly lead cannot spawn worker")
                if (
                    self.plans is not None
                    and self.plans.state is not None
                    and self.plans.state.mode == "planning"
                ):
                    raise ToolError("plan_read_only", "Plan mode cannot start a modifying worker")
                if self.worktrees is None:
                    raise ToolError("worker_requires_git", "Worker requires a Git repository")
            if self.active_count() >= self.max_agents:
                raise ToolError("agent_limit_reached", "Wait or cancel an agent before spawning")
            if len(self.records) >= 64:
                raise ToolError("agent_total_limit", "This session has reached 64 children")
            identifier = uuid4().hex[:8]
            while identifier in self.records:
                identifier = uuid4().hex[:8]
            record = {
                "agent_id": identifier,
                "session_id": "agent-" + uuid4().hex,
                "kind": kind,
                "mode": mode,
                "role": role,
                "task": task,
                "task_id": task_id,
                "status": "starting",
                "workspace": self.children.workspace,
                "provider": provider.name,
                "model_id": provider.model_id,
                "pending": [],
                "final_text": "",
                "runs": 1,
                "generation": 1,
                "session_created": False,
                "worktree_id": None,
                "branch": None,
                "base": None,
            }
            async with self.board.lock:
                claimed = [self.board.claimed(task_id, identifier)] if task_id is not None else []
                await self.commit(agents=[record], tasks=claimed)
            startup = self.own(self.start(record, run_id, fork_from, fork_entry_ids))
            self.startups[identifier] = startup
        await startup
        return self.records[identifier]

    async def start(self, record, run_id, fork_from, fork_entry_ids):
        identifier, epoch = record["agent_id"], self.epoch
        session = None
        worktree = None
        try:
            if record["mode"] == "worker":
                creating = asyncio.create_task(
                    self.worktrees.create(base="HEAD", include_uncommitted=False)
                )
                try:
                    worktree = await asyncio.shield(creating)
                except asyncio.CancelledError:
                    worktree = await creating
                    record = {
                        **record,
                        "workspace": worktree["path"],
                        "worktree_id": worktree["id"],
                        "branch": worktree["branch"],
                        "base": worktree["base"],
                    }
                    await self.commit(agents=[record])
                    raise
                record = {
                    **record,
                    "workspace": worktree["path"],
                    "worktree_id": worktree["id"],
                    "branch": worktree["branch"],
                    "base": worktree["base"],
                }
                await self.commit(agents=[record])
            session = await self.children.open(
                record,
                self.member(identifier),
                create=True,
                fork_from=fork_from,
                fork_entry_ids=fork_entry_ids,
            )
            self.sessions[identifier] = session
            record = {**record, "session_created": True}
            await self.commit(agents=[record])
            await session.record_team_parent(
                {
                    "lead_session_id": (await self.lead_history.snapshot()).header.session_id,
                    "agent_id": identifier,
                    "mode": record["mode"],
                    "role": record["role"],
                }
            )
            await self.lead_history.append_entry(
                EntryDraft(
                    "team_agent",
                    run_id=run_id,
                    data={
                        key: record[key]
                        for key in ("agent_id", "session_id", "workspace", "mode", "kind", "role")
                    },
                )
            )
            async with self.lock:
                if self.epoch != epoch or self.records[identifier]["status"] != "starting":
                    raise asyncio.CancelledError
                record = {**record, "status": "running"}
                await self.commit(agents=[record])
                self.drivers[identifier] = self.own(
                    self.drive(
                        identifier, Message("team-start-" + identifier, "user", record["task"])
                    )
                )
            await self.emit("agent", record)
        except BaseException as error:
            if session is not None:
                await session.aclose()
                self.sessions.pop(identifier, None)
            if worktree is not None:
                await self.worktrees.update(
                    {**worktree, "worker_stopped": True, "status": "worker_start_failed"}
                )
            outcome = "cancelled" if isinstance(error, asyncio.CancelledError) else "failed"
            await self.finish(
                identifier, outcome, "", getattr(error, "code", "agent_start_failed"), notify=False
            )
            if isinstance(error, asyncio.CancelledError):
                raise
            raise ToolError("agent_start_failed", "Cannot open child: " + str(error)) from error
        finally:
            self.startups.pop(identifier, None)

    async def drive(self, identifier, message=None, *, answers=None):
        try:
            session = self.sessions.get(identifier)
            if session is None:
                session = await self.children.open(
                    self.records[identifier], self.member(identifier), create=False
                )
                self.sessions[identifier] = session
            result = await (
                session.resume(answers) if answers is not None else session.run(message)
            )
            if result.outcome == "needs_input":
                async with self.lock:
                    record = {
                        **self.records[identifier],
                        "status": "needs_approval",
                        "pending": plain_data(result.pending),
                    }
                    await self.commit(
                        agents=[record],
                        messages=[
                            self.inbox.notification(
                                record, "agent_needs_input", "User approval required", wake=False
                            )
                        ],
                    )
                await self.emit("approval", record)
            else:
                await self.finish(
                    identifier,
                    "completed" if result.outcome == "completed" else "failed",
                    result.final_text,
                )
        except asyncio.CancelledError:
            await self.finish(identifier, "cancelled", "", notify=False)
            raise
        except Exception as error:
            _LOG.warning("Child execution failed: %s", identifier, exc_info=True)
            await self.finish(
                identifier, "failed", str(error), getattr(error, "code", "agent_internal_error")
            )
        finally:
            if self.drivers.get(identifier) is asyncio.current_task():
                self.drivers.pop(identifier, None)
            await self.schedule_wakes()

    async def finish(self, identifier, status, text, error_code=None, *, notify=True):
        async with self.lock:
            original = self.records[identifier]
            if original["status"] in {"completed", "failed", "cancelled", "merged"}:
                return
            record = {
                **original,
                "status": status,
                "pending": [],
                "final_text": text[:8000],
                "error_code": error_code,
            }
            async with self.board.lock:
                messages = []
                if notify and not self.stopping and not self.closing and record["kind"] == "agent":
                    messages.append(
                        self.inbox.notification(
                            record, "agent_finished", f"{record['role']}: {status}\n{text}"
                        )
                    )
                await self.commit(
                    agents=[record],
                    tasks=self.board.settled(identifier, status, text),
                    messages=messages,
                )
        if record.get("worktree_id"):
            try:
                worktree = await self.worktrees.inspect(record["worktree_id"])
                await self.worktrees.update({**worktree, "worker_stopped": True})
            except (DomainError, OSError) as error:
                _LOG.warning(
                    "Worker stopped; worktree metadata retained: %s",
                    getattr(error, "code", type(error).__name__),
                )
        await self.emit("agent", record)
        if messages:
            await self.emit("wake", {})

    async def schedule_wakes(self):
        async with self.lock:
            if self.stopping or self.closing or self.closed or self.degraded:
                return
            for identifier in tuple(self.wake_eligible):
                record = self.records[identifier]
                if self.active_count() >= self.max_agents:
                    return
                if record["kind"] != "agent" or record["status"] not in {
                    "completed",
                    "failed",
                    "interrupted",
                }:
                    continue
                pending = self.inbox.pending(identifier)
                if (
                    not pending
                    or identifier not in self.wake_eligible
                    or identifier in self.cancelling
                ):
                    continue
                self.wake_eligible.pop(identifier, None)
                updated = {
                    **record,
                    "status": "running",
                    "pending": [],
                    "runs": record["runs"] + 1,
                    "generation": record["generation"] + 1,
                    "review": None,
                }
                await self.commit(agents=[updated])
                self.drivers[identifier] = self.own(self.wake_child(identifier))

    async def wake_child(self, identifier):
        input_id = "team-child-" + uuid4().hex
        messages = []
        try:
            messages = await self.inbox.reserve(identifier, "wake", input_id)
            session = self.sessions.get(identifier)
            if session is None:
                session = await self.children.open(
                    self.records[identifier], self.member(identifier), create=False
                )
                self.sessions[identifier] = session
            await session.cancel()
            message = Message(
                input_id,
                "user",
                "\n\n".join(team_message_text(m) for m in messages),
                provider_metadata={"team_wake": [m["id"] for m in messages]},
            )
            await self.drive(identifier, message)
        except asyncio.CancelledError:
            await self.finish(identifier, "cancelled", "", notify=False)
            raise
        except Exception as error:
            await self.finish(
                identifier, "failed", str(error), getattr(error, "code", "agent_start_failed")
            )
        finally:
            try:
                accepted = bool(messages) and await self.input_accepted(identifier, input_id)
            except DomainError as error:
                accepted = False
                _LOG.warning("Cannot confirm child inbox receipt: %s", error.code)
            ids = [m["id"] for m in messages]
            if identifier in self.cancelling or self.stopping:
                async with self.lock:
                    await self.commit(
                        messages=[{**self.inbox.records[key], "status": "dropped"} for key in ids]
                    )
            else:
                await (self.inbox.acknowledge(ids) if accepted else self.inbox.release(ids))
            if self.drivers.get(identifier) is asyncio.current_task():
                self.drivers.pop(identifier, None)

    async def answer(self, identifier, answers):
        async with self.lock:
            record = self.require_agent(identifier)
            if record["status"] != "needs_approval":
                raise ToolError("agent_not_waiting", "This agent has no pending approval")
            pending = record["pending"]
            if len(pending) == 1 and ("operation_id" in answers or "responses" in answers):
                validate_operation_answer(pending[0], answers)
            else:
                questions = {question["interrupt_id"]: question for question in pending}
                if not answers or not set(answers) <= set(questions):
                    raise ToolError(
                        "invalid_resume", "Answers must identify current child interrupts"
                    )
                for key, value in answers.items():
                    validate_operation_answer(questions[key], value)
            await self.commit(agents=[{**record, "status": "running", "pending": []}])
            self.drivers[identifier] = self.own(self.drive(identifier, answers=answers))
        return {"agent_id": identifier, "status": "running"}

    async def cancel(self, identifier):
        async with self.lock:
            record = self.require_agent(identifier)
            if record["status"] not in ACTIVE_AGENT_STATES:
                return {"agent_id": identifier, "cancelled": False, "status": record["status"]}
            self.cancelling.add(identifier)
            self.wake_eligible.pop(identifier, None)
            tasks = [
                task
                for task in (self.startups.get(identifier), self.drivers.get(identifier))
                if task is not None and task is not asyncio.current_task()
            ]
            for task in tasks:
                task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        session = self.sessions.get(identifier)
        if session is not None:
            await session.cancel()
        await self.finish(identifier, "cancelled", "", notify=False)
        async with self.lock:
            await self.commit(
                messages=[{**m, "status": "dropped"} for m in self.inbox.pending(identifier)]
            )
        return {"agent_id": identifier, "cancelled": True, "status": "cancelled"}

    async def cancel_all(self, reason="lead_cancelled"):
        self.stopping = True
        self.epoch += 1
        await asyncio.gather(
            *(
                self.cancel(key)
                for key, value in tuple(self.records.items())
                if value["status"] in ACTIVE_AGENT_STATES
            ),
            return_exceptions=True,
        )

    async def run_blocking(
        self,
        task,
        provider,
        *,
        role="Readonly delegate",
        run_id=None,
        fork_from=None,
        fork_entry_ids=None,
    ):
        record = await self.spawn(
            "readonly",
            role,
            task,
            provider,
            run_id=run_id,
            kind="delegate",
            fork_from=fork_from,
            fork_entry_ids=fork_entry_ids,
        )
        identifier = record["agent_id"]
        try:
            driver = self.drivers.get(identifier)
            if driver is not None:
                await driver
            return self.records[identifier]
        except asyncio.CancelledError:
            await self.cancel(identifier)
            raise

    async def review(self, identifier):
        record = self.require_agent(identifier)
        if record["mode"] != "worker":
            raise ToolError("not_a_worker", "Only workers have worktree changes")
        changes = await self.worktrees.changes(record["worktree_id"])
        if record["status"] == "completed":
            async with self.lock:
                current = self.records[identifier]
                if (
                    current["status"] == "completed"
                    and current["generation"] == record["generation"]
                ):
                    await self.commit(agents=[{**current, "review": changes["fingerprint"]}])
        return {"agent_id": identifier, **changes}

    async def merge_preview(self, identifier, action="merge"):
        self.require_open()
        record = self.require_agent(identifier)
        if record["mode"] != "worker":
            raise ToolError("not_a_worker", "Only workers can merge")
        if record["status"] == "merged":
            raise ToolError("already_merged", "This worker was already merged")
        if record["status"] != "completed":
            raise ToolError("agent_not_completed", "Wait for a successful stopped worker")
        error = merge_order_error(record, self.board.records, self.records)
        if error:
            raise ToolError(error, "Merge dependencies first")
        if not record.get("review"):
            raise ToolError("review_required", "Call review_agent before merge_agent")
        return await self.worktrees.merge_preview(record["worktree_id"], record["review"], action)

    async def merge(self, identifier, preview, action="merge"):
        async with self.merge_lock:
            async with self.lock:
                self.require_open()
                record = self.require_agent(identifier)
                error = merge_order_error(record, self.board.records, self.records)
                if error:
                    raise ToolError(error, "Task dependencies changed during approval")
                if record.get("review") != preview.get("fingerprint"):
                    raise ToolError("review_stale", "Review changed during approval")
                if record["status"] != "completed":
                    raise ToolError("agent_not_completed", "Worker is active or not successful")
                await self.commit(
                    agents=[{**record, "status": "merging", "merge_preview": preview}]
                )
            cancelled = False
            try:
                operation = asyncio.create_task(
                    self.worktrees.merge_into_workspace(record["worktree_id"], preview, action)
                )
                try:
                    result = await asyncio.shield(operation)
                except asyncio.CancelledError:
                    cancelled = True
                    result = await operation
            except BaseException:
                async with self.lock:
                    await self.commit(agents=[record])
                raise
            response = await self.finalize_merge(identifier, record, result)
            if cancelled:
                raise asyncio.CancelledError
            return response

    async def finalize_merge(self, identifier, record, result):
        if not result.get("merged") and not result.get("resolution_pending"):
            await self.commit(agents=[record])
            return result
        if result.get("resolution_pending"):
            await self.commit(agents=[{**record, "resolution_pending": True, "review": None}])
            return result
        merged = {
            **record,
            "status": "merged",
            "merge_commit": result.get("merge_commit"),
            "resolution_pending": False,
        }
        await self.commit(agents=[merged])
        session = self.sessions.pop(identifier, None)
        if session is not None:
            await session.aclose()
        try:
            cleanup = await self.worktrees.complete_team_merge(
                record["worktree_id"], result.get("merge_commit")
            )
        except DomainError as error:
            cleanup = {"removed": False, "reason": error.code}
        return {**result, "cleanup": cleanup, "agent_id": identifier}

    async def wait_lead_input(self):
        while True:
            async with self.lock:
                self.changed.clear()
                if self.degraded:
                    raise StorageError(
                        "team_storage_error",
                        "Team persistence failed; reopen the session to recover",
                    )
                if self.inbox.pending("lead", wake_only=True) and not self.stopping:
                    return "input"
                active = [r for r in self.records.values() if r["status"] in ACTIVE_AGENT_STATES]
                if not active:
                    return "idle"
                if all(r["status"] == "needs_approval" for r in active):
                    return "blocked"
            await self.changed.wait()

    async def aclose(self):
        self.closing = True
        try:
            await self.cancel_all("close")
        finally:
            remaining = [task for task in tuple(self.tasks) if task is not asyncio.current_task()]
            for task in remaining:
                if not task.done():
                    task.cancel()
            await asyncio.gather(*remaining, return_exceptions=True)
            try:
                await asyncio.gather(
                    *(session.aclose() for session in tuple(self.sessions.values())),
                    return_exceptions=True,
                )
            finally:
                self.sessions.clear()
                self.closed = True
                await self.ledger.aclose()


class TeamMember:
    def __init__(self, team, agent_id):
        self.team, self.agent_id = team, agent_id

    async def flush_inbox(self, context):
        await self.team.inbox.flush(self.agent_id, context)

    async def settle_tool(self, result, *, accepted):
        operation_id = result if isinstance(result, str) else result.operation_id
        ids = () if isinstance(result, str) else result.details.get("team_message_ids", ())
        accepted = accepted and not isinstance(result, str) and not result.is_error
        await self.team.inbox.settle_tool(operation_id, accepted=accepted, message_ids=ids)
