"""Bind native feature products at the framework runtime boundary."""

from functools import partial

from pycode.agent import RunContext
from pycode.agent.module import AgentModule
from pycode.hooks import run_hook
from pycode.models.module import ModelsModule
from pycode.tools.module import ToolsModule


def build_runtime_bindings(
    *,
    modules=None,
    framework_extensions=(),
    model_filters=(),
    before_model=(),
    tool_extensions=(),
    native_bindings=(),
    calibrator=None,
    question_extensions=(),
):
    agent = modules.agent if modules is not None else AgentModule()
    models = modules.models if modules is not None else ModelsModule
    tools = modules.tools if modules is not None else ToolsModule()

    def settlement_factory(bound: RunContext):
        async def lifecycle(context, event, boundary, *, message="", error=""):
            await run_hook(bound, event, boundary, message=message, error=error)

        return tools.settlement(
            publisher=agent.publisher(bound),
            lifecycle=lifecycle,
            converter=models.to_framework,
            tool_extensions=tool_extensions,
            native_bindings=native_bindings,
        )

    return agent.bindings(
        to_framework=models.to_framework,
        from_assistant=models.from_assistant,
        make_framework_tool=partial(tools.framework_tool, native_bindings=native_bindings),
        select_model_tools=models.select_model_tools,
        calibrate_model_request=calibrator or models.calibrate_model_request,
        model_request_step=models.model_request_step,
        settlement_middleware_factory=settlement_factory,
        run_hook=run_hook,
        framework_extensions=framework_extensions,
        model_filters=model_filters,
        before_model=before_model,
        question_extensions=question_extensions,
    )


def build_agent_runtime(model, **options):
    return AgentModule().runtime(model, bindings=build_runtime_bindings(), **options)
