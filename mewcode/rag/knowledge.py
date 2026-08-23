"""Workspace-private knowledge base with light local hybrid retrieval."""

from __future__ import annotations

import csv
import hashlib
import html
import json
import math
import os
import re
import tempfile
import time
import zipfile
from dataclasses import asdict, dataclass, field
from html.parser import HTMLParser
from pathlib import Path
from typing import Iterable
from xml.etree import ElementTree

import jieba
from langchain_text_splitters import MarkdownHeaderTextSplitter, RecursiveCharacterTextSplitter
from rank_bm25 import BM25Okapi

from mewcode.config import KnowledgeEmbeddingConfig
from mewcode.rag.embedding import (
    EmbeddingClient,
    KnowledgeEmbeddingError,
    OpenAICompatibleEmbeddingClient,
)


INDEX_SCHEMA_VERSION = 2
MAX_DOCUMENT_BYTES = 32 * 1024 * 1024
CHUNK_SIZE = 1500
CHUNK_OVERLAP = 200
RETRIEVAL_LIMIT = 20
DEFAULT_TOP_K = 6
DEFAULT_TOKEN_BUDGET = 4_500
RRF_K = 60

_TEXT_EXTENSIONS = {
    ".adoc", ".csv", ".htm", ".html", ".json", ".md", ".markdown",
    ".rst", ".tsv", ".txt", ".yaml", ".yml",
}
_OFFICE_EXTENSIONS = {".docx", ".xlsx"}
_OPTIONAL_EXTENSIONS = {".pdf"}
_CAMEL_BOUNDARY = re.compile(r"(?<=[a-z0-9])(?=[A-Z])")
_TERM_PARTS = re.compile(r"[A-Za-z0-9]+|[\u3400-\u9fff\u3040-\u30ff\uac00-\ud7af]+")
_W_NS = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"
_S_NS = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"


class KnowledgeIndexNotFoundError(RuntimeError):
    pass


class KnowledgeIndexCompatibilityError(RuntimeError):
    pass


@dataclass(frozen=True)
class KnowledgeUpdateReport:
    documents: int
    chunks: int
    updated: int
    unchanged: int
    removed: int
    skipped: int

    def __str__(self) -> str:
        return (
            f"documents={self.documents}, chunks={self.chunks}, updated={self.updated}, "
            f"unchanged={self.unchanged}, removed={self.removed}, skipped={self.skipped}"
        )


@dataclass(frozen=True)
class KnowledgeStatus:
    indexed: bool
    stale: bool
    documents: int
    chunks: int
    knowledge_root: Path
    index_root: Path
    reason: str = ""


@dataclass(frozen=True)
class KnowledgeSearchResult:
    path: str
    heading: str
    start_line: int
    end_line: int
    content: str
    fused_score: float
    bm25_rank: int | None
    vector_rank: int | None

    @property
    def explanation(self) -> str:
        routes: list[str] = []
        if self.bm25_rank is not None:
            routes.append(f"BM25 #{self.bm25_rank}")
        if self.vector_rank is not None:
            routes.append(f"vector #{self.vector_rank}")
        return ", ".join(routes) or "hybrid retrieval"


@dataclass(frozen=True)
class KnowledgeSearchResponse:
    query: str
    results: list[KnowledgeSearchResult]
    bm25_candidates: int
    vector_candidates: int
    dropped_for_budget: int

    def explain(self) -> str:
        lines = [
            f"query: {self.query}",
            f"BM25 candidates: {self.bm25_candidates}, vector candidates: {self.vector_candidates}",
        ]
        if self.dropped_for_budget:
            lines.append(f"dropped for token budget: {self.dropped_for_budget}")
        for number, result in enumerate(self.results, 1):
            heading = f" {result.heading}" if result.heading else ""
            lines.append(
                f"{number}. {result.path}:{result.start_line}-{result.end_line}{heading} "
                f"[{result.explanation}; score={result.fused_score:.4f}]"
            )
        return "\n".join(lines)


@dataclass
class _Chunk:
    path: str
    heading: str
    start_line: int
    end_line: int
    content: str
    vector: list[float]

    @property
    def id(self) -> tuple[str, int, int]:
        return self.path, self.start_line, self.end_line

    def embedding_input(self) -> str:
        return "\n".join(part for part in (self.path, self.heading, self.content) if part)

    @classmethod
    def from_json(cls, value: dict) -> _Chunk:
        return cls(
            path=str(value["path"]),
            heading=str(value.get("heading", "")),
            start_line=int(value["start_line"]),
            end_line=int(value["end_line"]),
            content=str(value["content"]),
            vector=[float(item) for item in value.get("vector", [])],
        )


@dataclass
class _Document:
    path: str
    content_hash: str
    size_bytes: int
    modified_ns: int
    chunks: list[_Chunk] = field(default_factory=list)

    @classmethod
    def from_json(cls, value: dict) -> _Document:
        return cls(
            path=str(value["path"]),
            content_hash=str(value["content_hash"]),
            size_bytes=int(value["size_bytes"]),
            modified_ns=int(value["modified_ns"]),
            chunks=[_Chunk.from_json(item) for item in value.get("chunks", [])],
        )


@dataclass(frozen=True)
class _StoredIndex:
    documents: list[_Document]
    embedding_identity: str
    embedding_dimensions: int


class KnowledgeRagService:
    """Indexes manually maintained documents under ``.mewcode/knowledge``."""

    def __init__(
        self,
        workspace: str | Path,
        embedding_config: KnowledgeEmbeddingConfig | None = None,
        embedding_client: EmbeddingClient | None = None,
    ) -> None:
        root = Path(workspace).resolve()
        self.knowledge_root = root / ".mewcode" / "knowledge"
        self.index_root = root / ".mewcode" / "knowledge-index"
        self._index_file = self.index_root / "index.json"
        self._catalog_file = self.index_root / "catalog.json"
        if embedding_client is not None:
            self._embedding_client = embedding_client
        elif embedding_config is not None:
            self._embedding_client = OpenAICompatibleEmbeddingClient(embedding_config)
        else:
            self._embedding_client = None
        self._bm25: BM25Okapi | None = None
        self._bm25_chunk_ids: list[tuple[str, int, int]] = []

    async def synchronize(self) -> KnowledgeUpdateReport:
        client = self._require_embedding_client()
        self.knowledge_root.mkdir(parents=True, exist_ok=True)
        previous = self._load_index(allow_missing=True)
        can_reuse = previous is not None and previous.embedding_identity == client.identity
        old_by_path = {document.path: document for document in previous.documents} if previous else {}
        reusable_by_path = old_by_path if can_reuse else {}
        documents: list[_Document] = []
        updated = unchanged = skipped = 0

        for source in self._source_files():
            relative = source.relative_to(self.knowledge_root).as_posix()
            previous_document = reusable_by_path.get(relative)
            try:
                stat = source.stat()
            except OSError:
                continue
            if (
                previous_document is not None
                and previous_document.size_bytes == stat.st_size
                and previous_document.modified_ns == stat.st_mtime_ns
            ):
                documents.append(previous_document)
                unchanged += 1
                continue

            content = _extract_document(source)
            if not content or not content.strip():
                skipped += 1
                continue
            digest = hashlib.sha256(content.encode("utf-8")).hexdigest()
            if previous_document is not None and previous_document.content_hash == digest:
                documents.append(
                    _Document(
                        relative,
                        digest,
                        stat.st_size,
                        stat.st_mtime_ns,
                        previous_document.chunks,
                    )
                )
                unchanged += 1
                continue
            documents.append(
                _Document(
                    relative,
                    digest,
                    stat.st_size,
                    stat.st_mtime_ns,
                    _chunk_document(relative, content),
                )
            )
            updated += 1

        documents.sort(key=lambda document: document.path)
        changed_chunks = [
            chunk
            for document in documents
            if document.path not in reusable_by_path
            or document is not reusable_by_path.get(document.path)
            for chunk in document.chunks
            if not chunk.vector
        ]
        await self._embed_chunks(changed_chunks, client)
        dimensions = _vector_dimensions(documents)
        if previous is not None and previous.embedding_identity == client.identity and previous.embedding_dimensions != dimensions:
            raise KnowledgeEmbeddingError(
                "Embedding API changed vector dimensions; index was left unchanged. "
                "Check the configured model and run /knowledge index again."
            )

        removed = len(set(old_by_path) - {document.path for document in documents})
        # All remote work completed successfully: only now replace the persisted index.
        self._save_index(documents, client.identity, dimensions)
        self._save_catalog(documents)
        self._rebuild_bm25(documents)
        return KnowledgeUpdateReport(
            documents=len(documents),
            chunks=sum(len(document.chunks) for document in documents),
            updated=updated,
            unchanged=unchanged,
            removed=removed,
            skipped=skipped,
        )

    def status(self) -> KnowledgeStatus:
        index = self._load_index(allow_missing=True)
        if index is None:
            return KnowledgeStatus(
                indexed=False,
                stale=bool(list(self._source_files())),
                documents=0,
                chunks=0,
                knowledge_root=self.knowledge_root,
                index_root=self.index_root,
                reason="Knowledge index has not been created.",
            )

        reason = ""
        stale = False
        if self._embedding_client is not None and index.embedding_identity != self._embedding_client.identity:
            stale = True
            reason = "Embedding endpoint or model changed."
        previous = {
            document.path: (document.size_bytes, document.modified_ns)
            for document in index.documents
        }
        current: dict[str, tuple[int, int]] = {}
        for source in self._source_files():
            try:
                stat = source.stat()
            except OSError:
                continue
            current[source.relative_to(self.knowledge_root).as_posix()] = (
                stat.st_size,
                stat.st_mtime_ns,
            )
        if current != previous:
            stale = True
            reason = reason or "Knowledge source files changed."
        return KnowledgeStatus(
            indexed=True,
            stale=stale,
            documents=len(index.documents),
            chunks=sum(len(document.chunks) for document in index.documents),
            knowledge_root=self.knowledge_root,
            index_root=self.index_root,
            reason=reason,
        )

    async def search(
        self,
        query: str,
        *,
        top_k: int = DEFAULT_TOP_K,
        token_budget: int = DEFAULT_TOKEN_BUDGET,
    ) -> KnowledgeSearchResponse:
        query = query.strip()
        if not query:
            raise ValueError("query must not be blank")
        if top_k < 1 or token_budget < 1:
            raise ValueError("invalid search options")
        client = self._require_embedding_client()
        index = self._load_index()
        if index.embedding_identity != client.identity:
            raise KnowledgeIndexCompatibilityError(
                "Knowledge index was built with a different Embedding endpoint or model. "
                "Run /knowledge index."
            )
        chunks = [chunk for document in index.documents for chunk in document.chunks]
        if not chunks:
            return KnowledgeSearchResponse(query, [], 0, 0, 0)
        self._ensure_bm25(index.documents)
        bm25_hits = self._bm25_hits(query)
        query_vector = _normalize_vector((await client.embed([query]))[0])
        if len(query_vector) != index.embedding_dimensions:
            raise KnowledgeIndexCompatibilityError(
                "Embedding API returned a different vector dimension than the knowledge index. "
                "Run /knowledge index after correcting the Embedding model."
            )
        vector_hits = _vector_hits(chunks, query_vector, RETRIEVAL_LIMIT)
        results: list[KnowledgeSearchResult] = []
        used = dropped = 0
        for chunk, score, bm25_rank, vector_rank in _rrf_fuse(bm25_hits, vector_hits):
            if len(results) >= top_k:
                break
            estimated = max(1, (len(chunk.content) + 3) // 4)
            if results and used + estimated > token_budget:
                dropped += 1
                continue
            results.append(
                KnowledgeSearchResult(
                    chunk.path,
                    chunk.heading,
                    chunk.start_line,
                    chunk.end_line,
                    chunk.content,
                    score,
                    bm25_rank,
                    vector_rank,
                )
            )
            used += estimated
        return KnowledgeSearchResponse(
            query, results, len(bm25_hits), len(vector_hits), dropped
        )

    def catalog_reminder(self) -> str:
        if not self._catalog_file.is_file():
            return ""
        try:
            entries = json.loads(self._catalog_file.read_text(encoding="utf-8"))
        except (OSError, ValueError, json.JSONDecodeError):
            return ""
        if not entries:
            return ""
        lines = ["# Available private knowledge"]
        for entry in entries[:20]:
            suffix = f": {entry['summary']}" if entry.get("summary") else ""
            lines.append(f"- {entry['title']} ({entry['path']}){suffix}")
        lines.append(
            "Use KnowledgeSearch when these documents are relevant. "
            "If the index is stale, ask the user to run /knowledge index."
        )
        return "\n".join(lines)

    def _require_embedding_client(self) -> EmbeddingClient:
        if self._embedding_client is None:
            raise KnowledgeEmbeddingError(
                "Knowledge retrieval is disabled or missing knowledge.embedding configuration."
            )
        return self._embedding_client

    async def _embed_chunks(
        self, chunks: list[_Chunk], client: EmbeddingClient
    ) -> None:
        for start in range(0, len(chunks), 32):
            batch = chunks[start : start + 32]
            vectors = await client.embed([chunk.embedding_input() for chunk in batch])
            if len(vectors) != len(batch):
                raise KnowledgeEmbeddingError(
                    f"Embedding API returned {len(vectors)} vectors for a batch of {len(batch)} chunks"
                )
            expected_dimension: int | None = None
            for chunk, vector in zip(batch, vectors):
                vector = _normalize_vector(vector)
                if expected_dimension is None:
                    expected_dimension = len(vector)
                if len(vector) != expected_dimension:
                    raise KnowledgeEmbeddingError(
                        "Embedding API returned inconsistent vector dimensions in one batch"
                    )
                chunk.vector = vector

    def _ensure_bm25(self, documents: list[_Document]) -> None:
        if self._bm25 is None:
            self._rebuild_bm25(documents)

    def _rebuild_bm25(self, documents: list[_Document]) -> None:
        chunks = [chunk for document in documents for chunk in document.chunks]
        self._bm25_chunk_ids = [chunk.id for chunk in chunks]
        self._bm25 = BM25Okapi([_bm25_tokens(chunk) for chunk in chunks]) if chunks else None

    def _bm25_hits(self, query: str) -> list[tuple[_Chunk, float, int]]:
        index = self._load_index()
        chunks_by_id = {
            chunk.id: chunk for document in index.documents for chunk in document.chunks
        }
        if self._bm25 is None:
            return []
        query_tokens = _tokenize(query)
        scores = self._bm25.get_scores(query_tokens)
        ordered = sorted(
            (
                (self._bm25_chunk_ids[position], float(score))
                for position, score in enumerate(scores)
                if score > 0
            ),
            key=lambda item: (-item[1], item[0]),
        )[:RETRIEVAL_LIMIT]
        # In a one-document corpus, Okapi BM25 can assign every query term an
        # IDF of zero. Preserve exact lexical matches instead of reporting no
        # BM25 candidates solely because the private knowledge base is small.
        if not ordered and query_tokens:
            query_terms = set(query_tokens)
            fallback = [
                (
                    chunk.id,
                    float(len(query_terms.intersection(_bm25_tokens(chunk)))),
                )
                for chunk in chunks_by_id.values()
            ]
            ordered = sorted(
                (item for item in fallback if item[1] > 0),
                key=lambda item: (-item[1], item[0]),
            )[:RETRIEVAL_LIMIT]
        return [
            (chunks_by_id[chunk_id], score, rank)
            for rank, (chunk_id, score) in enumerate(ordered, 1)
        ]

    def _source_files(self) -> Iterable[Path]:
        if not self.knowledge_root.is_dir():
            return []
        extensions = _TEXT_EXTENSIONS | _OFFICE_EXTENSIONS | _OPTIONAL_EXTENSIONS
        return sorted(
            path
            for path in self.knowledge_root.rglob("*")
            if path.is_file()
            and not path.is_symlink()
            and path.suffix.casefold() in extensions
            and _safe_size(path) <= MAX_DOCUMENT_BYTES
        )

    def _load_index(self, allow_missing: bool = False) -> _StoredIndex | None:
        if not self._index_file.is_file():
            if allow_missing:
                return None
            raise KnowledgeIndexNotFoundError(
                f"Knowledge index is missing. Add documents to {self.knowledge_root} and run /knowledge index."
            )
        try:
            payload = json.loads(self._index_file.read_text(encoding="utf-8"))
            if payload.get("schema_version") != INDEX_SCHEMA_VERSION:
                raise ValueError("index schema changed")
            identity = str(payload["embedding_identity"])
            dimensions = int(payload["embedding_dimensions"])
            documents = [_Document.from_json(item) for item in payload.get("documents", [])]
            if dimensions < 1 or any(
                len(chunk.vector) != dimensions
                for document in documents
                for chunk in document.chunks
            ):
                raise ValueError("invalid embedding vector")
            return _StoredIndex(documents, identity, dimensions)
        except (OSError, ValueError, TypeError, KeyError, json.JSONDecodeError) as error:
            raise KnowledgeIndexNotFoundError(
                f"Knowledge index at {self.index_root} is unreadable ({error}). Run /knowledge index again."
            ) from error

    def _save_index(
        self,
        documents: list[_Document],
        embedding_identity: str,
        embedding_dimensions: int,
    ) -> None:
        _write_json_atomic(
            self._index_file,
            {
                "schema_version": INDEX_SCHEMA_VERSION,
                "embedding_identity": embedding_identity,
                "embedding_dimensions": embedding_dimensions,
                "updated_at": time.time(),
                "documents": [asdict(document) for document in documents],
            },
        )

    def _save_catalog(self, documents: list[_Document]) -> None:
        entries: list[dict[str, str]] = []
        for document in documents:
            if not document.chunks:
                continue
            chunk = document.chunks[0]
            plain = re.sub(r"\s+", " ", chunk.content).strip()
            entries.append(
                {
                    "path": document.path,
                    "title": (chunk.heading or _first_line(chunk.content) or document.path)[:120],
                    "summary": plain[:240] + ("..." if len(plain) > 240 else ""),
                }
            )
        _write_json_atomic(self._catalog_file, entries)


def _chunk_document(path: str, content: str) -> list[_Chunk]:
    recursive = RecursiveCharacterTextSplitter(
        chunk_size=CHUNK_SIZE,
        chunk_overlap=CHUNK_OVERLAP,
        length_function=len,
    )
    sections = _markdown_sections(content) if path.casefold().endswith((".md", ".markdown")) else [(content, "", 0)]
    chunks: list[_Chunk] = []
    for section_text, heading, section_offset in sections:
        previous_offset = 0
        for piece in recursive.split_text(section_text):
            offset = _locate_piece(section_text, piece, previous_offset)
            previous_offset = offset + 1
            absolute_start = section_offset + offset
            start_line = content.count("\n", 0, absolute_start) + 1
            end_line = content.count("\n", 0, absolute_start + len(piece)) + 1
            chunks.append(_Chunk(path, heading, start_line, end_line, piece, []))
    return chunks


def _markdown_sections(content: str) -> list[tuple[str, str, int]]:
    splitter = MarkdownHeaderTextSplitter(
        headers_to_split_on=[("#", "h1"), ("##", "h2"), ("###", "h3")],
        strip_headers=False,
    )
    documents = splitter.split_text(content)
    if not documents:
        return [(content, "", 0)]
    sections: list[tuple[str, str, int]] = []
    cursor = 0
    for document in documents:
        text = document.page_content
        offset = _locate_piece(content, text, cursor)
        cursor = offset + max(1, len(text))
        heading = " > ".join(
            str(document.metadata[key])
            for key in ("h1", "h2", "h3")
            if document.metadata.get(key)
        )
        sections.append((text, heading, offset))
    return sections


def _locate_piece(source: str, piece: str, minimum_offset: int) -> int:
    offset = source.find(piece, max(0, minimum_offset))
    if offset >= 0:
        return offset
    offset = source.find(piece)
    return offset if offset >= 0 else max(0, minimum_offset)


def _tokenize(value: str) -> list[str]:
    normalized = _CAMEL_BOUNDARY.sub(" ", value or "").replace("_", " ")
    terms: list[str] = []
    for part in _TERM_PARTS.findall(normalized):
        if re.search(r"[\u3400-\u9fff\u3040-\u30ff\uac00-\ud7af]", part):
            terms.extend(token.strip().casefold() for token in jieba.lcut(part) if token.strip())
        else:
            terms.append(part.casefold())
    return terms


def _bm25_tokens(chunk: _Chunk) -> list[str]:
    return _tokenize("\n".join((chunk.path, chunk.heading, chunk.content)))


def _vector_dimensions(documents: list[_Document]) -> int:
    vectors = [chunk.vector for document in documents for chunk in document.chunks]
    if not vectors:
        # A knowledge directory with no readable documents remains a valid empty index.
        return 1
    dimensions = len(vectors[0])
    if dimensions < 1 or any(len(vector) != dimensions for vector in vectors):
        raise KnowledgeEmbeddingError("Embedding API returned inconsistent vector dimensions")
    return dimensions


def _normalize_vector(vector: list[float]) -> list[float]:
    if not vector:
        raise KnowledgeEmbeddingError("Embedding API returned an empty vector")
    if not all(isinstance(value, (int, float)) for value in vector):
        raise KnowledgeEmbeddingError("Embedding API returned a non-numeric vector")
    magnitude = math.sqrt(sum(float(value) * float(value) for value in vector))
    if magnitude == 0:
        raise KnowledgeEmbeddingError("Embedding API returned a zero vector")
    return [float(value) / magnitude for value in vector]


def _vector_hits(
    chunks: list[_Chunk], query_vector: list[float], limit: int
) -> list[tuple[_Chunk, float, int]]:
    scored = [
        (chunk, sum(left * right for left, right in zip(query_vector, chunk.vector)))
        for chunk in chunks
    ]
    scored.sort(key=lambda item: (-item[1], item[0].id))
    return [
        (chunk, score, rank)
        for rank, (chunk, score) in enumerate(scored[:limit], 1)
    ]


def _rrf_fuse(
    bm25: list[tuple[_Chunk, float, int]],
    vector: list[tuple[_Chunk, float, int]],
) -> list[tuple[_Chunk, float, int | None, int | None]]:
    values: dict[tuple[str, int, int], tuple[_Chunk, float, int | None, int | None]] = {}
    for chunk, _score, rank in bm25:
        values[chunk.id] = (chunk, 1 / (RRF_K + rank), rank, None)
    for chunk, _score, rank in vector:
        existing = values.get(chunk.id)
        if existing is None:
            values[chunk.id] = (chunk, 1 / (RRF_K + rank), None, rank)
        else:
            values[chunk.id] = (
                chunk,
                existing[1] + 1 / (RRF_K + rank),
                existing[2],
                rank,
            )
    return sorted(values.values(), key=lambda item: (-item[1], item[0].id))


def _safe_size(path: Path) -> int:
    try:
        return path.stat().st_size
    except OSError:
        return MAX_DOCUMENT_BYTES + 1


def _write_json_atomic(target: Path, payload: object) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(
        mode="w",
        encoding="utf-8",
        dir=target.parent,
        prefix="index-",
        suffix=".tmp",
        delete=False,
    ) as handle:
        json.dump(payload, handle, ensure_ascii=False, separators=(",", ":"))
        temporary = Path(handle.name)
    try:
        os.replace(temporary, target)
    finally:
        temporary.unlink(missing_ok=True)


def _extract_document(path: Path) -> str | None:
    try:
        extension = path.suffix.casefold()
        if extension in {".csv", ".tsv"}:
            return _extract_delimited(path, "\t" if extension == ".tsv" else ",")
        if extension in {".html", ".htm"}:
            parser = _HtmlTextExtractor()
            parser.feed(path.read_text(encoding="utf-8"))
            return parser.text()
        if extension == ".docx":
            return _extract_docx(path)
        if extension == ".xlsx":
            return _extract_xlsx(path)
        if extension == ".pdf":
            return _extract_pdf(path)
        return path.read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError, ValueError, zipfile.BadZipFile, ElementTree.ParseError):
        return None


def _extract_delimited(path: Path, delimiter: str) -> str:
    with path.open("r", encoding="utf-8", newline="") as handle:
        rows = list(csv.reader(handle, delimiter=delimiter))
    if not rows:
        return ""
    headers, lines = rows[0], ["[table]"]
    for row in rows[1:]:
        lines.append(
            ", ".join(
                f"{headers[index] if index < len(headers) and headers[index] else f'column{index + 1}'}={value}"
                for index, value in enumerate(row)
            )
        )
    return "\n".join(lines)


def _extract_docx(path: Path) -> str:
    with zipfile.ZipFile(path) as archive:
        root = ElementTree.fromstring(archive.read("word/document.xml"))
    lines: list[str] = []
    for paragraph in root.iter(f"{_W_NS}p"):
        text = "".join(node.text or "" for node in paragraph.iter(f"{_W_NS}t")).strip()
        if text:
            lines.append(text)
    return "\n".join(lines)


def _extract_xlsx(path: Path) -> str:
    with zipfile.ZipFile(path) as archive:
        shared: list[str] = []
        if "xl/sharedStrings.xml" in archive.namelist():
            root = ElementTree.fromstring(archive.read("xl/sharedStrings.xml"))
            shared = [
                "".join(node.text or "" for node in item.iter(f"{_S_NS}t"))
                for item in root.iter(f"{_S_NS}si")
            ]
        lines: list[str] = []
        worksheets = sorted(
            value
            for value in archive.namelist()
            if value.startswith("xl/worksheets/") and value.endswith(".xml")
        )
        for name in worksheets:
            lines.append(f"[sheet: {Path(name).stem}]")
            root = ElementTree.fromstring(archive.read(name))
            for row in root.iter(f"{_S_NS}row"):
                values: list[str] = []
                for cell in row.iter(f"{_S_NS}c"):
                    value = cell.findtext(f"{_S_NS}v", default="")
                    if cell.get("t") == "s" and value.isdigit() and int(value) < len(shared):
                        value = shared[int(value)]
                    values.append(value)
                if values:
                    lines.append(", ".join(values))
    return "\n".join(lines)


def _extract_pdf(path: Path) -> str | None:
    try:
        from pypdf import PdfReader
    except ImportError:
        return None
    reader = PdfReader(path)
    return "\n".join(
        f"[page {number}]\n{page.extract_text() or ''}"
        for number, page in enumerate(reader.pages, 1)
    )


class _HtmlTextExtractor(HTMLParser):
    _skip_tags = {"script", "style", "noscript", "nav", "footer"}
    _block_tags = {"h1", "h2", "h3", "h4", "h5", "h6", "li", "p"}

    def __init__(self) -> None:
        super().__init__()
        self._skip = 0
        self._tag = ""
        self._parts: list[str] = []
        self._lines: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        if tag in self._skip_tags:
            self._skip += 1
        if tag in self._block_tags:
            self._tag, self._parts = tag, []

    def handle_endtag(self, tag: str) -> None:
        if tag in self._skip_tags and self._skip:
            self._skip -= 1
        if tag == self._tag:
            value = " ".join("".join(self._parts).split())
            if value:
                self._lines.append(("# " if tag.startswith("h") else "") + value)
            self._tag, self._parts = "", []

    def handle_data(self, data: str) -> None:
        if not self._skip and self._tag:
            self._parts.append(html.unescape(data))

    def text(self) -> str:
        return "\n".join(self._lines)


def _first_line(content: str) -> str:
    return next(
        (line.lstrip("#").strip() for line in content.splitlines() if line.lstrip("#").strip()),
        "",
    )
