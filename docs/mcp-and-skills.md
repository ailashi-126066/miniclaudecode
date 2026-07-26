# MCP and Skills

## MCP

MCP 仅从用户配置加载，避免仓库中的恶意配置自动启动进程。

```yaml
mcp:
  servers:
    local-tools:
      transport: stdio
      command: ["java", "-jar", "C:/tools/my-mcp-server.jar"]
      launch-approved: true
      initialization-timeout-seconds: 20
      operation-timeout-seconds: 60
      risk: HIGH
    knowledge:
      transport: streamable-http
      url: https://mcp.example.com/mcp
      headers:
        Authorization: Bearer ${MCP_TOKEN}
      risk: HIGH
```

`launch-approved` 只批准 stdio server 启动，不批准其工具调用。连接失败会隔离并显示在 `/mcp`，不会让本地 Agent 无法启动。大 MCP 结果进入 content-addressed tool-result store。

当前 YAML header 是字面值，不执行环境变量插值；敏感 header 更适合由受控启动包装器注入。不要把真实 token 提交到仓库。

## Skills

发现顺序为用户目录、`.claude/skills`、`.mini-claude-code/skills`；同名时项目级优先。启动时系统 prompt 只包含名称和描述，模型必须调用 `skills:load_skill` 才读取正文。

```markdown
---
name: java-review
description: Review Java changes for concurrency and API compatibility.
---

# Java review

Inspect tests and public API before suggesting a change.
```

Skill 文件最大读取量受限，必须是 UTF-8，且不能通过符号链接逃逸根目录。Skill 中即使写着“无需审批”，也不会改变文件、命令、Web 或 MCP 的权限策略。
