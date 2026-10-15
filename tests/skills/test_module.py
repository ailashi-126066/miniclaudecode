"""Optional Skill assembly keeps discovery and approval policy within the feature."""

from types import SimpleNamespace

import pytest

from pycode.kernel import ToolCall, ToolContext, ToolError, ToolResult, ToolSpec
from pycode.skills.adapters.extension import SkillsToolExtension
from pycode.skills.module import SkillsModule, SkillsSettings
from pycode.skills.services.scope import SkillsScopeConstraint


def test_standalone_mounts_are_readonly_and_project_trust_is_explicit(tmp_path):
    user = tmp_path / "user"
    workspace = tmp_path / "project"
    (user / "skills").mkdir(parents=True)
    (workspace / ".pycode" / "skills").mkdir(parents=True)
    seen = []

    def backend(root, **options):
        seen.append((root, options))
        return SimpleNamespace(receipts={})

    module = SkillsModule(
        schema=None, process_runner=None, backend_factory=backend, skill_read_grant=None
    )
    settings = SkillsSettings(workspace, user)
    routes = module.standalone_routes(settings)
    assert "/skills/user/" in routes
    assert "/skills/project/" not in routes
    assert all(options == {"read_only": True} for _, options in seen)
    assert all(route.read_only for route in routes.values())
    assert set(module.standalone_extension(settings).sources) == set(routes)


def test_dynamic_lookup_requires_actual_sdk_disclosure(monkeypatch):
    tool = SimpleNamespace(spec=ToolSpec("dynamic", "dynamic", {}))
    resources = SimpleNamespace(get_tool=lambda name: tool)
    extension = SkillsToolExtension(resources, None, None)
    monkeypatch.setattr(
        "pycode.skills.adapters.extension.disclosed_skill_tool_names",
        lambda state: state["disclosed"],
    )
    assert extension.lookup("dynamic", state={"disclosed": ()}) is None
    assert extension.lookup("dynamic", state={"disclosed": ("dynamic",)}) is tool


@pytest.mark.asyncio
async def test_fork_read_denial_and_receipts_happen_at_distinct_boundaries():
    recorded = []

    class Recorder:
        async def selected_entry(self, path):
            return SimpleNamespace(name="review", mode="fork")

        async def record(self, path, result, entry_id):
            recorded.append((path, entry_id))

    extension = SkillsToolExtension(None, Recorder(), SimpleNamespace(dispatch=False))
    call = ToolCall("c", "read_file", {"file_path": "/skills/user/review/SKILL.md"})
    context = ToolContext("s", "r", "o", "/workspace", agent_id="lead")
    with pytest.raises(ToolError, match="Use task"):
        await extension.before_call(context, call)
    result = extension.prepare_result(call, ToolResult("c", "read_file", "body"))
    assert result.details["protected_content"] is True
    assert recorded == []
    await extension.after_persist(context, call, result, "entry")
    assert recorded == [(call.arguments["file_path"], "entry")]


@pytest.mark.asyncio
async def test_scope_preserves_instruction_reads_but_denies_external_tools(monkeypatch):
    constraint = SkillsScopeConstraint(None)

    async def allowed():
        return {"grep", "SkillSearch"}

    monkeypatch.setattr(constraint, "allowed", allowed)
    context = ToolContext("s", "r", "o", "/workspace")
    await constraint.authorize(
        context,
        ToolSpec("read_file", "read", {}),
        ToolCall("c", "read_file", {"file_path": "/skills/user/x/SKILL.md"}),
    )
    with pytest.raises(ToolError, match="Active Skill scope"):
        await constraint.authorize(
            context, ToolSpec("Bash", "shell", {}), ToolCall("b", "Bash", {})
        )


def test_reserving_runtime_tools_blocks_late_resource_shadowing():
    from pycode.skills.module import SkillsRun

    resources = SimpleNamespace(tools={}, reserved_tools=frozenset({"read_file"}))
    run = SkillsRun((), {}, SimpleNamespace(resources=resources), None, None)
    run.reserve_tools(("Bash", "AskUserQuestion"))
    assert resources.reserved_tools == frozenset({"read_file", "Bash", "AskUserQuestion"})
    resources.tools["Bash"] = object()
    with pytest.raises(ToolError, match="shadows a built-in"):
        run.reserve_tools(("Bash",))
