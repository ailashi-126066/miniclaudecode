"""Prepare configured session capabilities before assembling one run."""

from pycode.app.composition.outputs import build_outputs
from pycode.app.composition.runtime import build_runtime
from pycode.context import InputBudget
from pycode.kernel import ToolSpec, plain_data


async def build_configured_run(
    launcher, provider, store, run_id, *, modules, skills_session=None, mcp_session=None
):
    snapshot = await store.snapshot()
    outputs = build_outputs(modules, snapshot.header.session_id, store)
    skills_run = None
    if skills_session is not None:
        fork_delegate = None
        if launcher.config.allow_delegation and launcher.team_member is not None:

            async def invoke_fork(instruction, *, fork_from=None, fork_entry_ids=None):
                return await launcher.team_member.team.run_blocking(
                    instruction,
                    provider,
                    run_id=run_id,
                    fork_from=fork_from,
                    fork_entry_ids=fork_entry_ids,
                )

            fork_delegate = invoke_fork
        reserved = tuple(tool.spec.name for tool in launcher.tools)
        skills_run = await skills_session.prepare_run(
            outputs=outputs,
            sandbox=modules.sandbox.worker(),
            sandbox_settings=launcher.config.sandbox,
            passthrough=launcher.config.subprocess_passthrough,
            read_only=launcher.config.read_only,
            reserved_tools=(
                *reserved,
                "read_file",
                "write_file",
                "edit_file",
                "ls",
                "glob",
                "grep",
                "execute",
                "write_todos",
                "task",
            ),
            fork_delegate=fork_delegate,
        )
    mcp_run = (
        mcp_session.prepare_run(
            store,
            InputBudget.create(provider.context_window, provider.max_output_tokens).input_limit,
        )
        if mcp_session is not None and provider.supports_tools
        else None
    )
    projected = modules.projector().project(snapshot)
    inherited = tuple(
        ToolSpec(**plain_data(item)) for item in projected.checkpoint.get("model_tools", ())
    )
    return await build_runtime(
        launcher.config,
        provider,
        session_id=snapshot.header.session_id,
        run_id=run_id,
        journal=store,
        tools=launcher.tools,
        memory=launcher.memory,
        team_member=launcher.team_member,
        consumers=launcher.consumers,
        history_store=store,
        checkpointer=modules.agent.checkpoint(),
        summary_service=launcher.summary,
        model_resolver=launcher.model_resolver,
        event_journal=launcher.event_journal,
        configured_hooks=launcher.configured_hooks,
        owned_hook_tasks=launcher.hook_tasks,
        mcp_config_hash=launcher.mcp_config_hash,
        plans=launcher.plans,
        plan_backend=launcher.plan_backend,
        durable_inputs=launcher.durable_inputs,
        effective_tools=inherited,
        skills_run=skills_run,
        mcp_run=mcp_run,
        outputs=outputs,
        modules=modules,
    )
