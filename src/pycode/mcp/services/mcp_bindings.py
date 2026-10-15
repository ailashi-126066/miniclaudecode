"""Restore branch MCP declarations and expose only the selected model schemas."""


class MCPRuntimeBindings:
    def __init__(
        self,
        service,
        factory,
        controls,
        input_limit,
        *,
        pending=False,
        framework_tools=None,
        tool_boundary=None,
    ):
        self._service = service
        self._factory = factory
        self._controls = controls
        self._input_limit = input_limit
        self._pending = pending
        self._initialized = False
        self._framework_tools = framework_tools
        self._tool_boundary = tool_boundary

    def framework_tool(self, name):
        declaration = self._service.declaration(name)
        if declaration is None or self._framework_tools is None:
            return None
        return self._framework_tools(declaration)

    def tool_call(self, name):
        assert self._tool_boundary is not None
        return self._tool_boundary(self._service.declaration(name))

    def declaration(self, name):
        return self._service.declaration(name)

    async def refresh(self, registry):
        if not self._initialized:
            await self._service.initialize(pending=self._pending)
            self._initialized = True
        await self._service.refresh(registry, self._factory, self._controls, self._input_limit)
        dispatch = next(tool for tool in self._controls if tool.spec.name == "MCPCall")
        registry.register_dispatch("MCPCall", dispatch)
