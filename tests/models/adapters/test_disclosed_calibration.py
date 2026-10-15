"""The final request, rather than optional catalog candidates, controls execution."""

from types import SimpleNamespace

from pycode.kernel import ToolError, ToolSpec
from pycode.models.adapters.calibration import calibrate_model_request, model_tool_specs


async def test_final_declared_names_are_bound_without_selector():
    spec = ToolSpec("visible", "visible", {})
    context = SimpleNamespace(
        registry=SimpleNamespace(get_spec=lambda name: spec),
        model_tools=(),
        selected_tool_names=None,
        system_hash="",
        history_store=None,
        summary=None,
    )
    request = SimpleNamespace(
        runtime=SimpleNamespace(context=context),
        tools=[SimpleNamespace(name="visible")],
        system_message=None,
    )
    result = await calibrate_model_request(request, converter=None, estimate_text=lambda text: 0)
    assert result is request
    assert context.selected_tool_names == ("visible",)
    assert context.model_tools == (spec,)


def test_dynamic_resource_metadata_is_injected_only_for_final_tools():
    seen = []

    def missing(name):
        raise ToolError("unknown_tool", name)

    declared = ToolSpec("resource", "resource", {}, implementation_hash="version")

    def lookup(name):
        seen.append(name)
        return SimpleNamespace(spec=declared)

    context = SimpleNamespace(registry=SimpleNamespace(get_spec=missing))
    specs = model_tool_specs([SimpleNamespace(name="resource")], context, resource_lookup=lookup)
    assert specs == (declared,)
    assert seen == ["resource"]
