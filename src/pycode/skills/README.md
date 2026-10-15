# Skills

`module.py` assembles trusted catalogs, installation, version-pinned read-only routes,
Python resource tools, permission constraints and the native framework loader. Its
settings contain no application configuration object. Project sources require
`project_trusted=True`.

```python
from pycode.skills.module import SkillsModule, SkillsSettings

module = SkillsModule(
    schema=schema,
    process_runner=process_runner,
    backend_factory=backend_factory,
    skill_read_grant=skill_read_grant,
)
session = module.open_session(
    SkillsSettings(workspace, user_data), history, catalog_write_guard=catalog_write_guard
)
await session.initialize()
run = await session.prepare_run(
    outputs=outputs,
    sandbox=sandbox,
    sandbox_settings=sandbox_settings,
    reserved_tools=reserved_names,
)
```

The application installs `run.tools`, mounts `run.routes`, composes
`run.permission_constraint` with its parent gate, and injects `run.extension` and
`run.tool_extension` into the agent and settlement boundaries. Resource lookup only
permits SDK-disclosed tools; the final model declarations are checked again before
execution. Durable Skill receipts follow successful tool-result publication.
Optional fork delegates receive an injected async `run_blocking` callable; this
module owns context selection and Skill task instructions.

`SkillsProjection` reads persisted history only. It remains usable when runtime
Skills are disabled. Standalone runtime construction uses `standalone_routes` and
`standalone_extension` without scanning resource declarations. Skills owns no
long-lived background connection and has no independent close obligation.
