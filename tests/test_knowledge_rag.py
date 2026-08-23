from __future__ import annotations

from dataclasses import dataclass, field
from types import SimpleNamespace

import pytest

from mewcode.config import ConfigError, KnowledgeEmbeddingConfig, load_config
from mewcode.rag import (
    KnowledgeEmbeddingError,
    KnowledgeIndexCompatibilityError,
    KnowledgeIndexNotFoundError,
    KnowledgeRagService,
)
from mewcode.rag.embedding import OpenAICompatibleEmbeddingClient
from mewcode.tools.knowledge_search import KnowledgeSearchParams, KnowledgeSearchTool


@dataclass
class FakeEmbeddingClient:
    identity: str = "https://embedding.example/v1|test-embedding"
    dimensions: int = 2
    fail: bool = False
    calls: list[list[str]] = field(default_factory=list)

    async def embed(self, texts: list[str]) -> list[list[float]]:
        self.calls.append(list(texts))
        if self.fail:
            raise KnowledgeEmbeddingError("simulated provider failure")
        values: list[list[float]] = []
        for text in texts:
            normalized = text.casefold()
            if (
                "api" in normalized
                or "endpoint" in normalized
                or "request id" in normalized
                or "semantic" in normalized
            ):
                values.append([0.0, 1.0])
            else:
                values.append([1.0, 0.0])
        return values


def _config() -> KnowledgeEmbeddingConfig:
    return KnowledgeEmbeddingConfig(
        base_url="https://embedding.example/v1",
        api_key="test-key",
        model="test-embedding",
    )


@pytest.mark.asyncio
async def test_openai_compatible_embedding_client_batches_orders_and_normalizes(monkeypatch):
    requests: list[dict] = []

    class FakeEmbeddings:
        async def create(self, **kwargs):
            requests.append(kwargs)
            return SimpleNamespace(
                data=[
                    SimpleNamespace(index=1, embedding=[0.0, 5.0]),
                    SimpleNamespace(index=0, embedding=[3.0, 4.0]),
                ]
            )

    class FakeOpenAI:
        def __init__(self, **kwargs):
            self.kwargs = kwargs
            self.embeddings = FakeEmbeddings()

    monkeypatch.setattr("mewcode.rag.embedding.AsyncOpenAI", FakeOpenAI)
    client = OpenAICompatibleEmbeddingClient(_config())

    vectors = await client.embed(["first", "second"])

    assert requests == [{"model": "test-embedding", "input": ["first", "second"]}]
    assert vectors == [[0.6, 0.8], [0.0, 1.0]]


@pytest.mark.asyncio
async def test_hybrid_index_search_catalog_and_staleness(tmp_path):
    knowledge_root = tmp_path / ".mewcode" / "knowledge"
    knowledge_root.mkdir(parents=True)
    (knowledge_root / "product-spec.md").write_text(
        "# Refund policy\n"
        "Customers may request a refund within thirty days of payment.\n\n"
        "# API contract\n"
        "The create endpoint returns a request id.\n",
        encoding="utf-8",
    )
    (tmp_path / "src.py").write_text("SOURCE_CODE_MUST_NOT_BE_INDEXED = True\n", encoding="utf-8")
    embedding = FakeEmbeddingClient()
    service = KnowledgeRagService(tmp_path, _config(), embedding)

    report = await service.synchronize()
    refund = await service.search("how long can a customer request refund")
    api = await service.search("create endpoint request id")

    assert (report.documents, report.chunks, report.updated) == (1, 2, 1)
    assert refund.results[0].heading == "Refund policy"
    assert "thirty days" in refund.results[0].content
    assert api.results[0].heading == "API contract"
    assert "request id" in api.results[0].content
    assert "Refund policy (product-spec.md)" in service.catalog_reminder()
    assert service.status().stale is False
    # Indexing used one batch; each user question made exactly one embedding request.
    assert [len(call) for call in embedding.calls] == [2, 1, 1]

    (knowledge_root / "product-spec.md").write_text("# Refund policy\nUpdated policy\n", encoding="utf-8")
    assert service.status().stale is True


@pytest.mark.asyncio
async def test_markdown_recursive_chunks_keep_heading_lines_and_overlap(tmp_path):
    root = tmp_path / ".mewcode" / "knowledge"
    root.mkdir(parents=True)
    # Repeated lines exercise alignment after overlapping chunks.
    repeated = "重复内容 customer refund policy line\n" * 180
    (root / "long.md").write_text("# Policy\n" + repeated, encoding="utf-8")
    service = KnowledgeRagService(tmp_path, _config(), FakeEmbeddingClient())

    report = await service.synchronize()
    response = await service.search("refund policy")

    assert report.chunks > 1
    assert all(result.heading == "Policy" for result in response.results)
    assert all(result.start_line <= result.end_line for result in response.results)
    assert response.results[0].start_line >= 1


@pytest.mark.asyncio
async def test_bm25_supports_chinese_terms_and_title_metadata(tmp_path):
    root = tmp_path / ".mewcode" / "knowledge"
    root.mkdir(parents=True)
    (root / "退款说明.md").write_text(
        "# 退款政策\n退款期限为付款后30天。\n",
        encoding="utf-8",
    )
    service = KnowledgeRagService(tmp_path, _config(), FakeEmbeddingClient())
    await service.synchronize()

    response = await service.search("退款期限")

    assert response.results
    assert response.results[0].bm25_rank == 1
    assert response.results[0].heading == "退款政策"


@pytest.mark.asyncio
async def test_vector_only_and_rrf_hybrid_candidates(tmp_path):
    root = tmp_path / ".mewcode" / "knowledge"
    root.mkdir(parents=True)
    (root / "knowledge.md").write_text(
        "# Refund\nRefund period is thirty days.\n\n"
        "# API\nThe create endpoint returns a request id.\n",
        encoding="utf-8",
    )
    service = KnowledgeRagService(tmp_path, _config(), FakeEmbeddingClient())
    await service.synchronize()

    vector_only = await service.search("semantic concept")
    hybrid = await service.search("api endpoint")

    assert vector_only.results[0].heading == "API"
    assert vector_only.results[0].bm25_rank is None
    assert vector_only.results[0].vector_rank == 1
    assert hybrid.results[0].heading == "API"
    assert hybrid.results[0].bm25_rank == 1
    assert hybrid.results[0].vector_rank == 1


@pytest.mark.asyncio
async def test_embedding_batches_and_failure_never_overwrites_previous_index(tmp_path):
    root = tmp_path / ".mewcode" / "knowledge"
    root.mkdir(parents=True)
    # More than 32 sections verifies the provider batching limit.
    (root / "bulk.md").write_text(
        "\n".join(f"# Section {index}\n" + ("x" * 20) for index in range(35)),
        encoding="utf-8",
    )
    healthy = FakeEmbeddingClient()
    service = KnowledgeRagService(tmp_path, _config(), healthy)
    await service.synchronize()
    assert [len(call) for call in healthy.calls] == [32, 3]

    (root / "bulk.md").write_text("# Changed\nnew content\n", encoding="utf-8")
    failing = FakeEmbeddingClient(fail=True)
    failed_service = KnowledgeRagService(tmp_path, _config(), failing)
    with pytest.raises(KnowledgeEmbeddingError, match="simulated provider failure"):
        await failed_service.synchronize()

    # The atomic index is still the last successfully indexed version.
    assert KnowledgeRagService(tmp_path, _config(), healthy).status().stale is True


@pytest.mark.asyncio
async def test_model_identity_and_query_dimension_mismatch_require_reindex(tmp_path):
    root = tmp_path / ".mewcode" / "knowledge"
    root.mkdir(parents=True)
    (root / "guide.md").write_text("# Guide\nUseful private knowledge.\n", encoding="utf-8")
    first = FakeEmbeddingClient()
    service = KnowledgeRagService(tmp_path, _config(), first)
    await service.synchronize()

    switched = FakeEmbeddingClient(identity="https://embedding.example/v1|other-model")
    changed = KnowledgeRagService(tmp_path, _config(), switched)
    assert changed.status().stale is True
    with pytest.raises(KnowledgeIndexCompatibilityError, match="different Embedding"):
        await changed.search("guide")


@pytest.mark.asyncio
async def test_knowledge_search_tool_is_async_and_reports_sources(tmp_path):
    root = tmp_path / ".mewcode" / "knowledge"
    root.mkdir(parents=True)
    (root / "api.md").write_text("# API contract\nThe create endpoint returns a request id.\n", encoding="utf-8")
    client = FakeEmbeddingClient()
    service = KnowledgeRagService(tmp_path, _config(), client)
    await service.synchronize()
    tool = KnowledgeSearchTool(tmp_path, _config(), client)

    result = await tool.execute(KnowledgeSearchParams(query="create endpoint request id"))

    assert result.is_error is False
    assert "Knowledge sources:" in result.output
    assert "api.md:1-2" in result.output


@pytest.mark.asyncio
async def test_search_requires_index_and_configuration(tmp_path):
    configured = KnowledgeRagService(tmp_path, _config(), FakeEmbeddingClient())
    with pytest.raises(KnowledgeIndexNotFoundError, match="knowledge index"):
        await configured.search("requirements")
    with pytest.raises(KnowledgeEmbeddingError, match="disabled"):
        await KnowledgeRagService(tmp_path).synchronize()


def test_knowledge_config_is_opt_in_and_resolves_embedding_secret(tmp_path, monkeypatch):
    monkeypatch.setenv("EMBEDDING_API_KEY", "resolved-secret")
    config_file = tmp_path / "config.yaml"
    config_file.write_text(
        """
providers:
  - name: test
    protocol: openai-compat
    base_url: https://chat.example/v1
    model: chat-model
knowledge:
  enabled: true
  embedding:
    base_url: https://embedding.example/v1
    api_key: ${EMBEDDING_API_KEY}
    model: embedding-model
""",
        encoding="utf-8",
    )

    config = load_config(config_file)

    assert config.knowledge.enabled is True
    assert config.knowledge.embedding is not None
    assert config.knowledge.embedding.api_key == "resolved-secret"
    assert config.knowledge.embedding.identity.endswith("|embedding-model")


def test_enabled_knowledge_requires_complete_embedding_config(tmp_path):
    config_file = tmp_path / "config.yaml"
    config_file.write_text(
        """
providers:
  - name: test
    protocol: openai
    base_url: https://api.example/v1
    model: chat-model
knowledge:
  enabled: true
""",
        encoding="utf-8",
    )

    with pytest.raises(ConfigError, match="knowledge.embedding"):
        load_config(config_file)
