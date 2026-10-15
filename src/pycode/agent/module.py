"""The single feature-owned entry for framework runtime construction."""

from langgraph.checkpoint.memory import InMemorySaver

from pycode.agent.adapters.agent import AgentRuntime
from pycode.agent.adapters.bindings import RuntimeBindings
from pycode.agent.adapters.boundaries import AgentMessagePublisher


class AgentModule:
    def runtime(self, model, *, bindings: RuntimeBindings, **options) -> AgentRuntime:
        return AgentRuntime(model, bindings=bindings, **options)

    def bindings(
        self,
        *,
        to_framework,
        from_assistant,
        make_framework_tool,
        select_model_tools,
        calibrate_model_request,
        model_request_step,
        settlement_middleware_factory,
        run_hook,
        framework_extensions=(),
        model_filters=(),
        before_model=(),
        question_extensions=(),
    ) -> RuntimeBindings:
        return RuntimeBindings(
            to_framework=to_framework,
            from_assistant=from_assistant,
            make_framework_tool=make_framework_tool,
            select_model_tools=select_model_tools,
            calibrate_model_request=calibrate_model_request,
            model_request_step=model_request_step,
            settlement_middleware_factory=settlement_middleware_factory,
            run_hook=run_hook,
            framework_extensions=tuple(framework_extensions),
            model_filters=tuple(model_filters),
            before_model=tuple(before_model),
            question_extensions=tuple(question_extensions),
        )

    def publisher(self, context) -> AgentMessagePublisher:
        return AgentMessagePublisher(context)

    def checkpoint(self) -> InMemorySaver:
        return InMemorySaver()

    @staticmethod
    def selector_required_names() -> tuple[str, ...]:
        return ("ls", "read_file", "glob", "grep")
