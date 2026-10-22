# MCP

MCP 提供会话连接、当前分支工具目录和原生工具执行。Tools 组件负责统一 ToolSearch；MCP 不再提供 MCPCall，也没有独立工具选择模型。

同次发现快照生成目录和 LangChain 工具。FastMCP 处理协议及分页，执行前重新校验远端 schema 和权限标记。取消或断线可能产生效果未知记录，不自动重跑。不支持 resources、prompts、elicitation。
