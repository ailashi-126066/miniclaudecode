# Sandbox

`module.SandboxModule` owns platform backend selection, required worker isolation, Bash discovery,
command construction and the Python Skill read grant. It receives `SandboxSettings`, an isolated
worker flag, validated ordinary environment names and optional Bash PATH prefixes. It does not load
application configuration or credentials.

`backend()` supplies normal isolation, `worker()` adds the required-isolation wrapper when enabled,
`bash(outputs)` supplies an executable tool, `bash_spec()` returns the pure declaration without
probing a host shell, and `process_runner` / `skill_read_grant` are explicit
capabilities supplied to Hooks and Skills. Policy identity contains settings and environment names,
never environment values. A Skill read grant only extends a required worker backend for its verified
resource root. Modules do not authorize tools; the Tools execution gate remains mandatory.

```python
from pycode.sandbox import SandboxSettings
from pycode.sandbox.module import SandboxModule

sandbox = SandboxModule(SandboxSettings(mode="on-auto"), passthrough=("LANG",))
backend = sandbox.worker()
```

The module owns no open lifetime. Individual process calls own cancellation, process termination and
stream finalization; Bash output writers are owned by the output store.
