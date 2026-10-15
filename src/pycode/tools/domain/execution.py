"""Select parallel execution only when every declared tool permits it."""

from pycode.kernel import ExecutionMode, ToolSpec


def batch_execution_mode(requested: ExecutionMode, tools: tuple[ToolSpec, ...]) -> ExecutionMode:
    if requested == "sequential":
        return "sequential"
    if any(tool.execution_mode == "sequential" for tool in tools):
        return "sequential"
    return "parallel"
