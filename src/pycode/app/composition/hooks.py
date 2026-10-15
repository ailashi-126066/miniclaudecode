"""Connect Hook engines to scoped tool execution and lifetime events."""

import os

from pycode.app.composition.outputs import build_outputs
from pycode.kernel import EventStream


def build_hook_engine(
    hooks,
    history,
    actions,
    outputs,
    events,
    *,
    modules,
    snapshots=None,
    owned_tasks=None,
    plans=None,
    read_only=False,
):
    def executor_factory(tools, *, plans=None, read_only=False):
        return modules.tools.configured_executor(
            tools, events, outputs, snapshots=snapshots, plans=plans, read_only=read_only
        )

    return modules.hooks.engine(
        hooks,
        history,
        actions,
        events,
        bind_output=outputs.bind,
        executor_factory=executor_factory,
        owned_tasks=owned_tasks,
        plans=plans,
        read_only=read_only,
    )


def build_lifecycle_hook_engine(launcher, store, snapshot, lifetime, *, modules):
    events = EventStream(
        snapshot.header.session_id, lifetime, launcher.consumers, journal=launcher.event_journal
    )
    outputs = build_outputs(modules, snapshot.header.session_id, store)
    return build_hook_engine(
        launcher.configured_hooks,
        store,
        modules.hooks.actions(
            outputs,
            modules.sandbox.shell(),
            modules.sandbox.backend(),
            launcher.config.sandbox,
            dict(os.environ),
            process_runner=modules.sandbox.process_runner,
            passthrough=launcher.config.subprocess_passthrough,
            delegate=modules.team.delegate(
                launcher.team_member.team, launcher.lifecycle_provider, store, lifetime
            )
            if launcher.config.allow_delegation and launcher.team_member is not None
            else None,
        ),
        outputs,
        events,
        modules=modules,
        snapshots=modules.snapshots.store(),
        owned_tasks=launcher.hook_tasks,
        plans=launcher.plans,
        read_only=launcher.config.read_only,
    )
