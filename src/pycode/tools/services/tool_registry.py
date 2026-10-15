"""Explicit registration; modules never self-register during import."""

from pycode.kernel import ExecutableTool as Tool
from pycode.kernel import ToolError, ToolSpec, clone_value
from pycode.tools.ports.dispatch import ToolDispatch
from pycode.tools.ports.tools import ArgumentValidator


class ToolRegistry:
    def __init__(self, validator: ArgumentValidator, tools: tuple[Tool, ...] = ()) -> None:
        self._validator = validator
        self._tools: dict[str, Tool] = {}
        self._specs: dict[str, ToolSpec] = {}
        self._dispatch: dict[str, ToolDispatch] = {}
        for tool in tools:
            self.register(tool)

    def register(self, tool: Tool) -> None:
        name = tool.spec.name
        if not name or name in self._tools:
            raise ToolError(
                "tool_name_conflict", f"Tool name is empty or already registered: {name}"
            )
        self._validator.check_schema(tool.spec.input_schema)
        self._tools[name] = tool
        self._specs[name] = clone_value(tool.spec)

    def get(self, name: str) -> Tool:
        tool = self._tools.get(name)
        if tool is None:
            raise ToolError("unknown_tool", f"Unknown tool: {name}")
        return tool

    def replace_source(self, prefix, tools):
        if any(not tool.spec.source.startswith(prefix) for tool in tools):
            raise ToolError("invalid_tool_source", "Replacement tools have a different source")
        preserved = tuple(
            tool for tool in self.list_tools() if not tool.spec.source.startswith(prefix)
        )
        validated = ToolRegistry(self._validator, (*preserved, *tools))
        self._tools, self._specs = validated._tools, validated._specs

    def list_tools(self) -> tuple[Tool, ...]:
        return tuple(self._tools[name] for name in sorted(self._tools))

    def get_spec(self, name: str) -> ToolSpec:
        self.get(name)
        return clone_value(self._specs[name])

    def list_specs(self) -> tuple[ToolSpec, ...]:
        return tuple(clone_value(self._specs[name]) for name in sorted(self._specs))

    def model_specs(self):
        return tuple(spec for spec in self.list_specs() if spec.model_visible)

    def register_dispatch(self, name, dispatcher: ToolDispatch):
        self.get(name)
        self._dispatch[name] = dispatcher

    def dispatcher(self, name):
        return self._dispatch.get(name)
