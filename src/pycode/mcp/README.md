# MCP

`module.py` owns configuration loading, schema checking, connection lifetime,
run catalogs, native tool boundaries and elicitation receipts. It receives
credentials explicitly and never imports the application configuration.

```python
from pycode.mcp.module import MCPModule, MCPSettings

module = MCPModule(schema=schema)
session = await module.open_session(
    MCPSettings(workspace, user_data), credentials, pending=pending_run
)
try:
    run = session.prepare_run(history, input_limit=12000)
    if run is not None:
        await run.refresh(registry)
finally:
    await session.aclose()
```

The application injects `run.native_binding` into framework tools and settlement,
`run.question_extension` into the runtime, and calls `run.refresh` before each
model input. The executor authorizes first; the native call scope then verifies
current remote schemas and owns elicitation. Run objects borrow connections and
never close a sibling run's session resources.

`connect=False` still reads configuration and verifies the pending configuration
hash, while creating no connections. A disabled module is not opened at all.
`MCPProjection` and `MCPModule.view_specs` use historical native values without
reading configuration or starting processes.
