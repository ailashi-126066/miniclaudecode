# Security model

## 文件系统

所有本地文件工具接收工作区相对路径。解析同时检查 lexical normalization、真实路径与符号链接，工作区外路径会拒绝。写入使用临时文件和原子替换；审批请求绑定目标、before hash 和 diff hash，避免 TOCTOU 后应用过期审批。

## 命令与网络

命令由 `ProcessBuilder` 启动：Windows 选择 PowerShell，Linux/macOS 选择 POSIX shell。分类器识别删除、权限、系统管理、网络下载和其他高风险模式；安全测试/构建命令可以直接执行，高风险命令要求审批。

Web Fetch 仅允许 HTTP(S)，手动处理并重新校验最多五次重定向，限制连接/请求时间和响应字节。localhost、私网和 link-local 地址需要审批，已知云元数据地址硬阻断。DNS 在每次重定向后重新分类。

## MCP 与 Skills

stdio MCP 会启动本地进程，因此必须在用户配置中显式设置 `launch-approved: true`；项目配置不会加载 MCP。每次 MCP 工具调用仍由本地 Registry 生成审批，服务端声明不能直接提升权限。Streamable HTTP 使用非 legacy SSE transport。

Skill 仅把指令文本送给模型，按需读取时再次验证真实路径和符号链接。Skill 不执行代码，也不会改变工具权限。

## Secrets 与审计

JSONL codec 会按字段名清理 authorization、api-key、token、secret，并替换当前配置已解析出的 Key。不要把真实密钥放进项目配置、prompt、Skill 或命令参数；异常仍可能包含第三方库生成的上下文，因此生产演示前应检查日志。

## 审批范围

菜单支持 once、turn、file、permanent 和 reject。永久规则写入用户目录 `permissions.json`。建议面试演示默认使用 once；只有明确理解目标范围时才使用 permanent。
