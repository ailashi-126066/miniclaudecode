"""Only fields reduced by tool settlement; independent of agent graph state."""

from typing import Annotated

from langchain.agents import AgentState


def latest_failure_field(previous, current):
    return current


class ToolSettlementState(AgentState):
    failure_tool: Annotated[str, latest_failure_field]
    failure_arguments: Annotated[str, latest_failure_field]
    failure_code: Annotated[str, latest_failure_field]
    failure_count: Annotated[int, latest_failure_field]
