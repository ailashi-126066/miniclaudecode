# MewCode Python

MewCode 的 Python 版本：一个在终端中运行的 AI 编程助手，支持工具调用、MCP、权限控制、worktree、多人协作模式和本地知识库检索。

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

# 启动远程 WebSocket 模式
uv run mewcode --remote
```

## 轻量知识库检索

知识库默认关闭。启用后，文档放在 `.mewcode/knowledge/`，索引保存在 `.mewcode/knowledge-index/`。向量由远程 OpenAI 兼容 Embedding API 生成，本机不下载或加载模型。

在 `.mewcode/config.yaml` 中追加：

```yaml
knowledge:
  enabled: true
  embedding:
    base_url: https://your-openai-compatible-endpoint/v1
    api_key: ${EMBEDDING_API_KEY}
    model: your-embedding-model
```

检索链路：

```text
文档切分（langchain-text-splitters）
  → BM25（rank-bm25 + jieba）
  → 远程 Embedding
  → 本地余弦相似度
  → RRF 融合
```

在交互界面中使用：

```text
/knowledge index
/knowledge status
/knowledge 你的问题
```

## 开发与测试

```bash
uv run pytest
```

## 说明

- `.mewcode/`、`.venv/`、`config.yaml` 等本地状态和凭据均不会被提交。
- 当前默认工作分支为 `appmod/java-upgrade-20260728154039`，历史提交包含 Java 阶段；后续提交为 Python 实现。
