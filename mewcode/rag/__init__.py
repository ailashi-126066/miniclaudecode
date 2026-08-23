"""Workspace-private knowledge-base retrieval."""

from mewcode.rag.embedding import KnowledgeEmbeddingError
from mewcode.rag.knowledge import (
    KnowledgeIndexCompatibilityError,
    KnowledgeIndexNotFoundError,
    KnowledgeRagService,
    KnowledgeSearchResponse,
    KnowledgeSearchResult,
    KnowledgeStatus,
    KnowledgeUpdateReport,
)

__all__ = [
    "KnowledgeIndexNotFoundError",
    "KnowledgeIndexCompatibilityError",
    "KnowledgeEmbeddingError",
    "KnowledgeRagService",
    "KnowledgeSearchResponse",
    "KnowledgeSearchResult",
    "KnowledgeStatus",
    "KnowledgeUpdateReport",
]
