# MewCode Python

MewCode 的 Python 版本：一个在终端中运行的 AI 编程助手，支持工具调用、MCP、权限控制、worktree 和多人协作模式。

本仓库保留了原 Java 项目的 Git 提交历史；当前分支的最新实现为 Python。

## 环境要求

- Python 3.11 或更高版本
- [uv](https://docs.astral.sh/uv/)

## 安装

```bash
git clone https://github.com/ailashi-126066/miniclaudecode.git
cd miniclaudecode
uv sync
```

## 配置模型服务

在项目根目录创建 `.mewcode/config.yaml`。该文件已被 Git 忽略，请不要提交 API Key。

```yaml
providers:
  - name: openai
    protocol: openai
    base_url: https://api.openai.com/v1
    model: gpt-4.1-mini
    api_key: ${OPENAI_API_KEY}

permission_mode: default
```

PowerShell 中可先设置环境变量：

```powershell
$env:OPENAI_API_KEY = "你的 API Key"
```

也可以将 `protocol` 改为 `openai-compat`，接入 OpenAI 兼容接口；支持的协议还有 `anthropic`。

## 运行

```bash
# 交互式终端界面
uv run mewcode

# 单次非交互调用
uv run mewcode -p "解释当前项目结构"
```

## macOS 沙箱

OS 级沙箱仅支持 macOS Seatbelt，默认关闭。终端中使用 `/sandbox on` 开启沙箱并保留常规权限审批，使用 `/sandbox on-auto` 对沙箱内的 Bash 命令自动放行；`/sandbox off` 关闭，`/sandbox` 查看状态。

沙箱限制文件写入和网络访问，当前策略允许读取文件。只有后端可用且实际挂载到 Bash 时，自动放行才生效；其他系统使用常规权限流程。

## 记忆与 Skills

自动记忆集中写入 Markdown 文件：用户偏好和纠正反馈保存在 `~/.mewcode/memories.md`，项目知识和参考资料保存在项目的 `.mewcode/memories.md`。使用 `/memory` 查看、`/memory edit` 查看文件位置、`/memory clear` 清空。

Skills 支持带 YAML frontmatter 的独立 `.md` 文件和目录中的 `SKILL.md`。隔离执行使用 `mode: fork`，上下文范围使用 `context: full`、`recent` 或 `none`；不支持 `context: fork` 和 `skill.yaml + prompt.md` 格式。

内置 Skills 包含 `/commit`（提交）、`/review`（审查）、`/test`（测试）和 `/backend-interview`（后端面试）。项目和用户的同名 Skill 可覆盖内置版本。审查和面试使用隔离执行及 `allowedTools` 白名单；后端面试附带 `parse_resume` 工具，支持 Markdown/TXT 简历。外部 Skill 安装流程保留。

工具结果处理沿用发送副本机制：单条超过 50,000 字符时保存全文并发送预览；同一条消息的结果合计超过 200,000 字符时优先处理较大的结果。超过最近 10 次最终回答的旧结果，大于 2,000 字符时在发送副本中缩短为约前 200 字符。替换决定保存到当前会话的 `tool-results/replacement_records.jsonl`，原有历史不被发送前处理修改。

工具执行等待模型本次回复结束，再按 `is_concurrency_safe` 分批：连续的安全工具可以并发；修改、命令等非安全工具单独执行，批次间等待完成。需要人工确认的调用使用交互式执行。

文件修改使用权限检查和工具自身校验：编辑旧文本必须唯一匹配，现有文件仍要求先读取。worktree 任务完成后返回结果并保留有改动的目录及分支，不自动提交改动或创建集成分支；合并由后续明确的 Git 操作完成。

## 开发与测试

```bash
uv run pytest
```

## 说明

- `.mewcode/`、`.venv/`、`config.yaml` 等本地状态和凭据均不会被提交。
- 当前默认工作分支为 `appmod/java-upgrade-20260728154039`，历史提交包含 Java 阶段；后续提交为 Python 实现。
