from pycode.kernel import ToolResult, ToolSpec


class Probe:
    def __init__(self, name):
        self.spec = ToolSpec(
            name,
            "Synthetic read tool",
            {"type": "object", "properties": {}},
            category="read",
            execution_mode="parallel",
        )
        self.calls = 0

    async def execute(self, context, arguments):
        self.calls += 1
        return ToolResult(context.call_id, self.spec.name, "probe result")
