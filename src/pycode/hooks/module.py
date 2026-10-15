"""Own Hook configuration loading, action implementations and lifecycle engines."""

import asyncio
from collections.abc import Awaitable, Callable, Mapping
from copy import copy
from dataclasses import dataclass
from pathlib import Path

from pycode.hooks.adapters.actions import ConfiguredHookActions
from pycode.hooks.adapters.config import load_hooks
from pycode.hooks.domain.hooks import Hook
from pycode.hooks.ports.hooks import HookActions
from pycode.hooks.ports.runtime import HookExecutorFactory, HookPlanGuard
from pycode.hooks.services.hooks import HookEngine
from pycode.kernel import EventSink, HistoryJournal, data_hash, plain_data
from pycode.kernel.adapters.path_io import owned_io
from pycode.sandbox import OutputSink, ProcessRunner, SandboxBackend, SandboxSettings


@dataclass(frozen=True)
class _OutputBinding:
    bind: Callable[[str, str], Awaitable[None]]


class HooksModule:
    def scoped(self, workspace, user_data, *, trusted=False, worker=False, allow_delegation=True):
        scoped = copy(self)
        scoped.workspace, scoped.user_data = workspace, user_data
        scoped.trusted, scoped.worker = trusted, worker
        scoped.allow_delegation = allow_delegation
        return scoped

    def __init__(
        self,
        workspace: Path,
        user_data: Path,
        *,
        trusted: bool = False,
        worker: bool = False,
        allow_delegation: bool = True,
        enabled: bool = True,
    ) -> None:
        self.workspace = workspace
        self.user_data = user_data
        self.trusted = trusted
        self.worker = worker
        self.allow_delegation = allow_delegation
        self.enabled = enabled

    async def configuration(self) -> tuple[Hook, ...]:
        if not self.enabled:
            return ()
        roots = [("user", self.user_data / "hooks.json")]
        if self.trusted:
            roots.append(("project", self.workspace / ".pycode" / "hooks.json"))
        hooks = await owned_io(load_hooks, tuple(roots))
        if self.worker:
            return tuple(hook for hook in hooks if hook.action.type == "prompt")
        if not self.allow_delegation:
            return tuple(hook for hook in hooks if hook.action.type != "agent")
        return hooks

    @staticmethod
    def config_hash(hooks: tuple[Hook, ...]) -> str:
        return data_hash([plain_data(hook) for hook in hooks]) if hooks else ""

    @staticmethod
    def actions(
        outputs: OutputSink,
        shell: str,
        sandbox: SandboxBackend,
        settings: SandboxSettings,
        environment: Mapping[str, str],
        *,
        process_runner: ProcessRunner,
        passthrough: tuple[str, ...] = (),
        delegate: object | None = None,
    ) -> HookActions:
        return ConfiguredHookActions(
            outputs,
            shell,
            sandbox,
            settings,
            environment,
            delegate,
            process_runner=process_runner,
            passthrough=passthrough,
        )

    @staticmethod
    def engine(
        hooks: tuple[Hook, ...],
        history: HistoryJournal,
        actions: HookActions,
        events: EventSink,
        *,
        bind_output: Callable[[str, str], Awaitable[None]],
        executor_factory: HookExecutorFactory,
        owned_tasks: dict[str, asyncio.Task] | None = None,
        plans: HookPlanGuard | None = None,
        read_only: bool = False,
    ) -> HookEngine:
        return HookEngine(
            hooks,
            history,
            actions,
            _OutputBinding(bind_output),
            events,
            executor_factory=executor_factory,
            owned_tasks=owned_tasks,
            plans=plans,
            read_only=read_only,
        )
