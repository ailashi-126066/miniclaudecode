# Plans

`module.PlansModule` owns session Plan files, the current-process approval prompt, PlanService, exit
control tool and the pure history projection contribution. Inputs are the plans directory and an
explicit workspace backend factory. It does not import Tools adapters or the application.

```python
from pathlib import Path
from pycode.plans.module import PlansModule
from pycode.tools.module import ToolsModule

# Application-root assembly supplies the backend capability explicitly.
plans = PlansModule(Path("state/plans"), backend_factory=ToolsModule().backend)
```

`session(session_id, history, prompt=...)` supplies `PlanSession(service, files, tools)`; the caller
awaits `service.initialize()` at the existing session-open boundary. `files`, `prompt`, `exit_tool`
and `exit_spec` also support diagnostic/history-only assembly. `projection()` supplies pure Plan
projection without loading files or creating a runtime.

The file backend restricts every operation to the active Plan path and restricts search targets
before reading files. There are no persistent handles to close. Approval interrupts belong to the
current run; resumed sessions preserve the existing explicit Plan initialization and review rules.
