# Team

负责进程内 asyncio 团队、任务板、收件箱、取消，以及 worker Git worktree 的审查和集成。
固定 readonly/worker 权限不能由临时角色文字扩大。领域事实和服务不导入 app。

`module.py:TeamModule` 组装团队服务、worktree manager、lead/child tools 和事件 relay。
输入为 child-session/repository 回调、snapshot factory、事件与明确的并发/权限设置；
不创建模型、会话适配器或其他功能内部实现。

应用组装层的 Git 能力可以单独建立：

```python
from pathlib import Path
from pycode.snapshot.module import SnapshotModule
from pycode.team.module import TeamModule

worktrees = TeamModule().worktrees(
    Path("/project"), Path("/state/worktrees"), snapshot_factory=SnapshotModule.factory
)
```

团队拥有 ledger 与子任务。取消会等待已派出的子任务收拢；关闭释放 ledger。worker 合并仍
绑定被审查的 tree/目标 HEAD，并逐次审批。子作用域由根显式重绑定已选模块；它不选择默认实现。
