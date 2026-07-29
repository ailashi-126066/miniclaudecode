# Configuration

用户配置位于 `~/.mini-claude-code/config.yaml`，项目覆盖配置位于
`<workspace>/.mini-claude-code/config.yaml`。对象字段递归合并；项目配置禁止出现
明文 `api-key`，也禁止覆盖用户控制的 `security` 策略。

无需手工编辑 YAML 也可以运行配置向导：

```powershell
java -jar agent-cli\target\mini-claude-code.jar config
```

在交互式 REPL 中可输入 `/config setup`。向导支持 OpenAI-compatible、Anthropic 和 Ollama，会掩码读取 API Key，并保留用户配置里已有的 Provider 与 MCP 字段。保存后重启 MiniClaudeCode，新 active provider 即会生效。

```yaml
active-provider: deepseek
providers:
  deepseek:
    type: openai-compatible
    base-url: https://api.deepseek.com/v1
    api-key-env: DEEPSEEK_API_KEY
    model: deepseek-chat
    temperature: 0.2
    max-output-tokens: 8192
    thinking: false
    timeout-seconds: 120
    max-retries: 3
    output-protocol: json
    max-output-repairs: 2
```

Provider 类型：

- `anthropic`：可省略 `base-url`；需要 Key。自定义网关的 `base-url` 若未包含 `/v1` 会自动补全（`https://gateway.example.com` 与 `https://gateway.example.com/v1` 等价）。
- `openai-compatible`：支持 OpenAI、DeepSeek、通义兼容网关及自建兼容服务；可自定义 `base-url`（应包含版本前缀，如 `https://api.deepseek.com/v1`；只写主机名时会自动补 `/v1`，自定义前缀如 `/api/v3` 则原样保留）。
- `ollama`：需要本地 `base-url`，不要求 Key。

Key 解析优先级是 `api-key-env` 指向的环境变量，其次是用户配置中的 `api-key`。允许在用户配置中写明文 Key 是为了本地使用方便，但应限制文件权限并避免同步到网盘；更推荐环境变量。

`/provider`、`/model` 和 `/thinking on|off` 只影响当前 CLI 进程。Provider 客户端按
profile、模型、thinking 模式和最大输出 Token 分别缓存，因此切换 thinking 会创建匹配
模式的客户端：Anthropic 会设置 thinking budget，OpenAI-compatible 会设置 reasoning
effort，Ollama 会把 think 标志传给后端。具体模型仍必须实际支持推理输出。

`max-retries` 控制当前 profile 每次模型调用遇到可重试错误时的最大重试次数，取值
`0..10`；认证、配置和非法请求错误不会重试。

`output-protocol` 控制最终回答的终止契约：

- `natural-language`：非空自然语言即为有效终止，适合 Claude/Ollama 等模型。
- `json`：要求 `{"status":"completed","final":"..."}`，适合需要显式完成标志的模型。

无效输出会被反馈给模型重新格式化，最多执行 `max-output-repairs` 次（`0..5`）。修复
耗尽后任务明确失败，不会把不合法输出猜测成成功。

## Shell 黑白名单

命令策略只能放在用户配置：

```yaml
security:
  shell:
    allowlist-only: false
    allow-prefixes:
      - rg
      - git status
      - git diff
      - get-content
    deny-fragments:
      - rm -rf
      - git reset --hard
      - git clean -f
      - diskpart
```

deny 始终优先，不能用一次审批绕过。命中 allow 前缀的命令仍会经过高危检查，避免
`git` 或 shell 之类过宽前缀放行危险子命令。未命中的命令在
`allowlist-only: false` 时进入风险分类和审批；设为 `true` 时直接拒绝。

## RAG / Embeddings

代码索引的向量模型通过 `rag.embedding` 配置：

```yaml
rag:
  embedding:
    provider: fast        # fast（默认）或 remote
    dimensions: 384
    # remote 需要以下字段：
    # base-url: https://api.openai.com/v1
    # api-key-env: OPENAI_API_KEY
    # model: text-embedding-3-small
    # dimensions: 1536
    # timeout-seconds: 30
```

- `fast`（默认）：离线哈希嵌入。零依赖、零下载、可复现，适合演示与 CI；语义质量有限（本质上是 BM25 的补充信号）。
- `remote`：任意 OpenAI-compatible `/v1/embeddings` 端点（OpenAI、DeepSeek、llama.cpp server、LM Studio 等）。`dimensions` 必须与模型实际返回一致——每次响应都会校验，不一致会给出明确报错而不是污染索引。

索引会持久化 embedding 身份（provider/端点/模型/维度）。切换任何一项都会触发一次全量重建：不同模型的向量不可比较，Lucene 也拒绝同一字段混合维度。重建前会先探测新后端可用性——端点不可达时旧索引（含 BM25 部分）保持原样可用。

## Prompt Cache 与使用量

Anthropic 原生 Provider 自动缓存稳定的 system message 与工具定义，并请求返回缓存诊断数据。OpenAI-compatible Provider 使用上游自动 Prompt Cache；只有网关在 `usage` 中返回 `cached_tokens` 时才能统计命中。MiniClaudeCode 将不同 Provider 统一为总输入 Token、缓存读取 Token和缓存写入 Token，并写入 JSONL `MODEL_USAGE` 事件。

交互会话输入 `/usage` 可查看累计值。命中率定义为 `cacheReadTokens / inputTokens`。切换或恢复会话时统计相互隔离；恢复已有会话时会从 JSONL 重建。不要把它与 RAG 未变化文件复用率或工具账本复用混为一谈。
