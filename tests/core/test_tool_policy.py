from pycode.core.tools import ToolSpec, batch_execution_mode


def test_one_sequential_tool_forces_batch_barrier():
    read = ToolSpec(name="Read", description="read", input_schema={}, execution_mode="parallel")
    command = ToolSpec(name="Bash", description="command", input_schema={})
    assert batch_execution_mode("parallel", (read, command)) == "sequential"
    assert batch_execution_mode("parallel", (read, read)) == "parallel"
    assert batch_execution_mode("sequential", (read, read)) == "sequential"
