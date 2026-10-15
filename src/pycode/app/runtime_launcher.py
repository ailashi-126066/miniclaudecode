"""Own active runtime, hook and summary lifetimes through explicit factories."""

import asyncio
from dataclasses import replace
from uuid import uuid4

from pycode.app.config import AppConfig
from pycode.context import ContextProjector, SummaryService
from pycode.hooks import HookContext
from pycode.kernel import (
    EventConsumer,
    EventJournal,
    Message,
    SessionTree,
    StorageError,
    ToolSpec,
    plain_data,
)
from pycode.kernel import ExecutableTool as Tool
from pycode.models import ProviderConfig
from pycode.session import SessionStore


class ConfiguredRuntimeLauncher:
    def __init__(
        self,
        config: AppConfig,
        tools: tuple[Tool, ...],
        consumers: tuple[EventConsumer, ...],
        event_journal: EventJournal,
        configured_hooks=(),
        mcp_connections=None,
        mcp_config_hash="",
        plans=None,
        plan_backend=None,
        durable_inputs=None,
        runtime_factory=None,
        model_resolver=None,
        hook_factory=None,
        prompt_factory=None,
        lifecycle_provider=None,
        memory=None,
        team_member=None,
    ) -> None:
        self.config = config
        self.model_resolver = model_resolver
        self._runtime_factory = runtime_factory
        self._hook_factory = hook_factory
        self._prompt_factory = prompt_factory
        self.lifecycle_provider = lifecycle_provider
        self.memory = memory
        self.team_member = team_member
        self.tools = tools
        self.consumers = consumers
        self.summary: SummaryService | None = None
        self.event_journal = event_journal
        self.configured_hooks = configured_hooks
        self.mcp_connections = mcp_connections
        self.mcp_config_hash = mcp_config_hash
        self.plans = plans
        self.plan_backend = plan_backend
        self.durable_inputs = durable_inputs
        self._hook_engines = []
        self.hook_tasks = {}
        self._lifecycle_engine = None
        self._lifecycle_context = None
        self._active_container = None

    async def startup(self, store):
        if not self.configured_hooks:
            return
        snapshot = await store.snapshot()
        lifetime = uuid4().hex
        engine = self._hook_factory(self, store, snapshot, lifetime)
        self._lifecycle_engine = engine
        self._lifecycle_context = HookContext(
            snapshot.header.session_id, lifetime, str(self.config.workspace), lifetime, "startup"
        )
        self._hook_engines.append(engine)
        await engine.fire(self._lifecycle_context)

    def abort_startup(self):
        self._lifecycle_engine, self._lifecycle_context = None, None

    async def run(
        self,
        provider: ProviderConfig,
        store: SessionStore,
        inputs: tuple[Message, ...],
        history: tuple[Message, ...],
    ):
        snapshot = await store.snapshot()
        path = SessionTree(snapshot.index).path(snapshot.leaf_id)
        start = next(
            (
                entry
                for entry in reversed(path)
                if entry.type == "custom"
                and entry.data.get("kind") == "run_start"
                and entry.data.get("input_id") == inputs[0].id
            ),
            None,
        )
        run_id = start.run_id if start is not None else uuid4().hex
        return await self.execute(provider, store, run_id, inputs=inputs, history=history)

    async def resume(self, provider, store, run_id, answers=None):
        return await self.execute(provider, store, run_id, resume=True, answers=answers)

    async def cancel_wait(self, run_id):
        container = self._active_container
        if container is None or container.context.run_id != run_id:
            return None
        self._active_container = None
        try:
            if container.context.summary is not None:
                await container.context.summary.cancel_background()
            if container.context.hooks is not None:
                await container.context.hooks.collect(cancel=True)
        finally:
            container.runtime.graph = None
            await container.aclose()
        return container.context.outputs

    async def execute(
        self, provider, store, run_id, *, inputs=(), history=(), resume=False, answers=None
    ):
        if not self.config.workspace.is_dir():
            raise StorageError("workspace_missing", "Execution workspace no longer exists")
        if resume:
            container = self._active_container
            if container is None or container.context.run_id != run_id:
                raise StorageError(
                    "run_expired", "Old execution has ended; start a new task from saved history"
                )
        else:
            await self._prompt_factory(store).refresh()
            container = await self._runtime_factory(self, provider, store, run_id)
            self._active_container = container
            if container.context.hooks is not None:
                self._hook_engines.append(container.context.hooks)
                container.owns_hooks = False
            self.summary = container.context.summary
            container.owns_summary = False
        keep_waiting = False
        try:
            if resume:
                result = await container.runtime.resume(container.context, answers)
            else:
                result = await container.runtime.run(inputs, container.context, history=history)
            keep_waiting = result.outcome == "needs_input"
            return result
        except StorageError as error:
            if resume and error.code in {
                "invalid_resume",
                "invalid_approval",
                "invalid_question_answer",
                "invalid_answers",
                "invalid_plan_answer",
            }:
                keep_waiting = True
            raise
        finally:
            if not keep_waiting:
                self._active_container = None
                await container.aclose()

    async def compact(self, provider, store):
        snapshot = await store.snapshot()
        container = await self._runtime_factory(self, provider, store, uuid4().hex)
        if container.context.hooks is not None:
            self._hook_engines.append(container.context.hooks)
            container.owns_hooks = False
        self.summary = container.context.summary
        container.owns_summary = False
        try:
            if container.context.mcp is not None:
                await container.context.mcp.refresh(container.context.registry)
                self.summary.set_tools(container.context.registry.model_specs())
            current = ContextProjector().project(await store.snapshot())
            effective = []
            for declaration in current.checkpoint.get("model_tools", ()):
                effective.append(ToolSpec(**plain_data(declaration)))
            if effective:
                self.summary.set_tools(tuple(effective))
            report = await self.summary.prepare(manual=True)
            if container.context.hooks is not None and report.applied_job_id is not None:
                await container.context.hooks.fire(
                    HookContext(
                        snapshot.header.session_id,
                        container.context.run_id,
                        str(self.config.workspace),
                        report.applied_job_id,
                        "compact",
                    )
                )
            return report
        finally:
            await container.aclose()

    async def aclose(self):
        if self._active_container is not None:
            active, self._active_container = self._active_container, None
            await active.aclose()
        engines, self._hook_engines = self._hook_engines, []
        try:
            try:
                if self._lifecycle_engine is not None:
                    context = self._lifecycle_context
                    await self._lifecycle_engine.fire(replace(context, event_name="shutdown"))
            finally:
                self._lifecycle_engine, self._lifecycle_context = None, None
                if engines:
                    await engines[0].collect(cancel=True)
        finally:
            try:
                results = await asyncio.gather(
                    *(engine.aclose() for engine in engines), return_exceptions=True
                )
                for result in results:
                    if isinstance(result, BaseException):
                        raise result
            finally:
                if self.summary is not None:
                    await self.summary.aclose()
                    self.summary = None
                if self.mcp_connections is not None:
                    await self.mcp_connections.aclose()

    async def invalidate_summary(self, reason):
        if self.summary is not None:
            await self.summary.invalidate(reason)

    async def reconcile_hook(self, store, operation_id, action, text="", is_error=False):
        snapshot = await store.snapshot()
        engine = self._hook_factory(self, store, snapshot, uuid4().hex)
        try:
            await engine.reconcile(operation_id, action, text, is_error)
        finally:
            await engine.aclose()
