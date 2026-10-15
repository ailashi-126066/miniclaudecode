# Application composition

统一入口是 `bootstrap.py:compose_application`，对外返回 Application。
`composition/` 只连接 application/session/run 的功能产物，包入口不导出旧构造函数集合。
任何业务功能都不导入 app；此层也不直接创建功能内部 adapters。

```python
from dataclasses import replace
from pathlib import Path
from pycode.app.bootstrap import compose_application, default_modules
from pycode.app.config import AppConfig

config = AppConfig(Path("/project"), Path("/state"), memory_mode="off")
selected = replace(default_modules(config), skills=None, mcp=None)
application = compose_application(config, modules=selected)
status = application.status()  # 不需要 Provider
```

默认模块集合是显式具名对象，可替换实现。集合为 None 表示默认集合；其中 skills/mcp 为
None 则禁用该运行能力，禁止先读默认配置再覆盖。历史纯投影独立配置，不启动这些运行能力。
子工作区调用所选模块自己的 scoped hook，保留实现和凭据源，不重建默认模块集合。

会话入口为 `await application.open_session(...)`；较低层服务验收使用
`await application.runtime(provider, ...)`，带历史时仍装配真实权限和收据能力。
后台模型同样是 `await application.background_model(...)`，调用方必须在 finally 关闭 owner。
所有关闭路径等待已拥有资源，不以后台 task 代替关闭完成。
