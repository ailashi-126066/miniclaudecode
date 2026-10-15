# Hooks

`module.HooksModule` owns trusted configuration loading/filtering, subprocess action construction and
HookEngine construction. Configuration inputs are workspace/user-data paths and trust, worker,
delegation and enabled flags. `configuration()` waits for owned file I/O on cancellation; disabled
configuration performs no reads. Workers retain prompt actions only, and disabled delegation removes
agent actions.

```python
from pathlib import Path
from pycode.hooks.module import HooksModule

hooks = HooksModule(Path.cwd(), Path("state"), trusted=False)
# Within an async session: configured = await hooks.configuration()
```

`actions` receives the output sink, shell, sandbox policy, environment and explicit process runner;
it never fetches another module implementation. Ordinary environment passthrough is filtered and its
names/settings are bound into the operation policy hash. Explicit Hook environment substitutions
retain their existing behavior.

`engine(hooks, history, actions, events, bind_output=..., executor_factory=...)` receives native
history/event capabilities, a persisted-output binding callback and a constrained tool executor
factory. Hooks does not instantiate a Tools executor. The engine owns background Hook tasks and
`await engine.aclose()` settles that lifetime; caller-supplied task tables preserve session/run
ownership. Unused validator/error-boundary/snapshot constructor placeholders have been removed.
