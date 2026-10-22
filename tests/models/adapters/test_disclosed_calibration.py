"""The final request, rather than optional catalog candidates, controls execution."""

from types import SimpleNamespace

from pycode.kernel import ToolError, ToolSpec
from pycode.models.adapters.calibration import calibrate_model_request, model_tool_specs


async def test_final_declared_names_are_bound_without_selector():
    spec = ToolSpec("visible", "visible", {})
    context = SimpleNamespace(
        registry=SimpleNamespace(get_spec=lambda name: spec),
        model_tools=(),
        disclosed_tool_names=None,
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
    assert context.disclosed_tool_names == ("visible",)
    assert context.model_tools == (spec,)


def test_framework_tool_schema_is_measured_without_a_second_resource_registry():
    def missing(name):
        raise ToolError("unknown_tool", name)

    context = SimpleNamespace(registry=SimpleNamespace(get_spec=missing))
    tool = SimpleNamespace(name="native", description="native", tool_call_schema={"type": "object"})
    specs = model_tool_specs([tool], context)
    assert specs == (ToolSpec("native", "native", {"type": "object"}, source="deepagents"),)
