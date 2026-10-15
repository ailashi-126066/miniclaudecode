# UI

负责 ConsoleOutput 和 Textual TUI。只消费 `ports/ui.py` 的 InteractiveSession(s) 与原生
视图事实，不创建数据库、模型、Skills、MCP 或执行器。

`module.py:UIModule` 的输入是输出流与应用提供的会话端口；输出 console 或 TUI。

```python
import sys
from pycode.ui.module import UIModule

ui = UIModule(sys.stdout)
console = ui.console("stream-json")
app = ui.tui(interactive_sessions)  # 调用方实现 InteractiveSessions
```

TUI 关闭自己打开的会话；底层资源由会话所有者关闭。应用入口在 `app/bootstrap.py`，
`app/composition/ui.py` 仅把应用命令适配为 UI 端口。可单独给此模块注入内存端口测试布局与交互。
