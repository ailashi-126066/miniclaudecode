from __future__ import annotations

import math
from typing import Protocol, Sequence

from openai import AsyncOpenAI

from mewcode.config import KnowledgeEmbeddingConfig


class KnowledgeEmbeddingError(RuntimeError):
    """The configured remote Embeddings endpoint could not produce usable vectors."""


class EmbeddingClient(Protocol):
    @property
    def identity(self) -> str: ...

    async def embed(self, texts: Sequence[str]) -> list[list[float]]: ...


class OpenAICompatibleEmbeddingClient:
    """Small adapter for any endpoint compatible with ``POST /v1/embeddings``."""

    def __init__(self, config: KnowledgeEmbeddingConfig) -> None:
        self._config = config
        self._client = AsyncOpenAI(api_key=config.api_key, base_url=config.base_url)

    @property
    def identity(self) -> str:
        return self._config.identity

    async def embed(self, texts: Sequence[str]) -> list[list[float]]:
        if not texts:
            return []
        try:
            response = await self._client.embeddings.create(
                model=self._config.model,
                input=list(texts),
            )
            ordered = sorted(response.data, key=lambda item: item.index)
            if len(ordered) != len(texts):
                raise KnowledgeEmbeddingError(
                    f"Embedding API returned {len(ordered)} vectors for {len(texts)} inputs"
                )
            return [_normalize(list(item.embedding)) for item in ordered]
        except KnowledgeEmbeddingError:
            raise
        except Exception as error:
            raise KnowledgeEmbeddingError(
                f"Embedding request failed for model '{self._config.model}': {error}"
            ) from error


def _normalize(vector: list[float]) -> list[float]:
    if not vector:
        raise KnowledgeEmbeddingError("Embedding API returned an empty vector")
    if not all(isinstance(value, (int, float)) for value in vector):
        raise KnowledgeEmbeddingError("Embedding API returned a non-numeric vector")
    magnitude = math.sqrt(sum(float(value) * float(value) for value in vector))
    if magnitude == 0:
        raise KnowledgeEmbeddingError("Embedding API returned a zero vector")
    return [float(value) / magnitude for value in vector]
