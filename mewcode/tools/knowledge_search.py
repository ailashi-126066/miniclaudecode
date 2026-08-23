from __future__ import annotations

from pathlib import Path

from pydantic import BaseModel, Field

from mewcode.config import KnowledgeEmbeddingConfig
from mewcode.rag.embedding import EmbeddingClient
from mewcode.rag import (
    KnowledgeEmbeddingError,
    KnowledgeIndexCompatibilityError,
    KnowledgeIndexNotFoundError,
    KnowledgeRagService,
)
from mewcode.tools.base import Tool, ToolResult


class KnowledgeSearchParams(BaseModel):
    query: str = Field(description="Question or keywords to search in private workspace knowledge")
    max_results: int = Field(default=5, ge=1, le=10, description="Maximum knowledge sections to return")


class KnowledgeSearchTool(Tool):
    name = "KnowledgeSearch"
    description = (
        "Search private workspace knowledge documents such as specifications, business rules, and API references. "
        "If the index is missing or stale, ask the user to run /knowledge index."
    )
    params_model = KnowledgeSearchParams
    category = "read"
    is_concurrency_safe = True

    def __init__(
        self,
        workspace: str | Path,
        embedding_config: KnowledgeEmbeddingConfig,
        embedding_client: EmbeddingClient | None = None,
    ) -> None:
        self._knowledge = KnowledgeRagService(
            workspace, embedding_config, embedding_client
        )

    async def execute(self, params: KnowledgeSearchParams) -> ToolResult:
        query = params.query.strip()
        if not query:
            return ToolResult(output="query is required", is_error=True)
        status = self._knowledge.status()
        if not status.indexed:
            return ToolResult(output=f"Knowledge index is not available. Add documents to {status.knowledge_root} and run /knowledge index.", is_error=True)
        if status.stale:
            suffix = f" ({status.reason})" if status.reason else ""
            return ToolResult(output=f"Knowledge index is stale. Run /knowledge index before relying on it.{suffix}", is_error=True)
        try:
            response = await self._knowledge.search(query, top_k=params.max_results)
        except (
            KnowledgeEmbeddingError,
            KnowledgeIndexCompatibilityError,
            KnowledgeIndexNotFoundError,
            ValueError,
        ) as error:
            return ToolResult(output=f"Knowledge search failed: {error}", is_error=True)
        if not response.results:
            return ToolResult(output="No relevant knowledge found.")
        sections = ["Knowledge sources:"]
        for result in response.results:
            heading = f" {result.heading}" if result.heading else ""
            sections.extend(["", f"- {result.path}:{result.start_line}-{result.end_line}{heading}", result.content])
        return ToolResult(output="\n".join(sections))
