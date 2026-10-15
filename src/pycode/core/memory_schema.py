"""Bounded LangMem content schemas; provenance is verified by the memory service."""

import hashlib
import re
from datetime import datetime
from typing import Annotated, Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, TypeAdapter, field_validator, model_validator

from pycode.core.errors import ToolError
from pycode.core.memory import SECRET_PATTERNS

Text = Annotated[str, Field(min_length=1, max_length=16000)]
ShortText = Annotated[str, Field(min_length=1, max_length=1000)]
Identity = Annotated[
    str, Field(min_length=1, max_length=128, pattern=r"^[A-Za-z0-9][A-Za-z0-9_.:-]*$")
]


def uuid_identity(key):
    if len(key) not in {32, 36}:
        return None
    try:
        return UUID(key).hex
    except ValueError:
        return None


def resolve_memory_key(records, key):
    if key in records:
        return key
    identity = uuid_identity(key)
    if identity is not None:
        return next((stored for stored in records if uuid_identity(stored) == identity), key)
    return key


class MemorySource(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    session_id: Identity
    entry_id: Identity
    quote: Text
    sha256: str | None = Field(default=None, pattern=r"^[a-f0-9]{64}$")

    @model_validator(mode="after")
    def check_quote(self):
        if not self.quote.strip():
            raise ValueError("Memory evidence requires a nonblank quote")
        digest = hashlib.sha256(self.quote.encode()).hexdigest()
        if self.sha256 is not None and self.sha256 != digest:
            raise ValueError("Evidence checksum does not match its quote")
        return self


class MemoryContent(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    title: Annotated[str, Field(min_length=1, max_length=200)]
    summary: ShortText
    body: Text
    sources: Annotated[list[MemorySource], Field(min_length=1, max_length=32)]

    @field_validator("title", "summary", "body")
    @classmethod
    def nonblank(cls, value):
        if not value.strip():
            raise ValueError("Memory text cannot be blank")
        return value

    @model_validator(mode="after")
    def no_secrets(self):
        text = self.model_dump_json()
        if any(re.search(pattern, text) for pattern in SECRET_PATTERNS):
            raise ValueError("Secrets must not enter long-term memory")
        return self


class SemanticMemory(MemoryContent):
    """Durable facts, user preferences and corrections supported by source evidence."""

    category: Literal["user_preference", "correction_feedback", "project_knowledge"]


class EpisodicMemory(MemoryContent):
    """Historical situation, observable actions and outcome; never hidden reasoning."""

    situation: ShortText
    actions: Annotated[list[ShortText], Field(min_length=1, max_length=16)]
    outcome: ShortText
    applicability: Annotated[list[ShortText], Field(min_length=1, max_length=16)]
    verification: Literal["observed", "verified", "unverified"]


class ProceduralMemory(MemoryContent):
    """Scoped behavior rule from explicit lasting feedback or a verified comparison."""

    applicability: Annotated[list[ShortText], Field(min_length=1, max_length=16)]
    origin: Literal["user_explicit", "model_evaluated"]


class SemanticValue(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    kind: Literal["SemanticMemory"]
    content: SemanticMemory


class EpisodicValue(SemanticValue):
    kind: Literal["EpisodicMemory"]
    content: EpisodicMemory


class ProceduralValue(SemanticValue):
    kind: Literal["ProceduralMemory"]
    content: ProceduralMemory


MemoryValue = Annotated[
    SemanticValue | EpisodicValue | ProceduralValue, Field(discriminator="kind")
]
MEMORY_VALUE = TypeAdapter(MemoryValue)


def validate_memory_value(value):
    from pycode.core.values import json_dumps

    try:
        return MEMORY_VALUE.validate_json(json_dumps(value, ensure_ascii=False)).model_dump(
            mode="json"
        )
    except (ValueError, TypeError) as error:
        raise ToolError(
            "invalid_memory_content", "Memory content does not match its schema"
        ) from error


class StoredMemory(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    value: MemoryValue
    created_at: datetime
    updated_at: datetime

    @model_validator(mode="after")
    def check_times(self):
        if self.created_at.tzinfo is None or self.updated_at.tzinfo is None:
            raise ValueError("Memory times require a timezone")
        if self.updated_at < self.created_at:
            raise ValueError("Memory update precedes creation")
        return self


class SourceCursor(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    entry_id: Identity
    offset: Annotated[int, Field(ge=0)] = 0


class MemoryOutputPosition(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    output_id: Annotated[str, Field(pattern=r"^output_[a-f0-9]{32}$")]
    character_offset: Annotated[int, Field(ge=0)] = 0
    line_offset: Annotated[int, Field(ge=1)] = 1
    byte_offset: Annotated[int, Field(ge=0)] = 0
    absolute_byte_offset: Annotated[int, Field(ge=0)] = 0


class SourceBatch(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    session_id: Identity
    branch_id: Identity
    entry_ids: Annotated[list[Identity], Field(min_length=1, max_length=64)]
    prefix_hash: Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
    start: SourceCursor
    end: SourceCursor
    changes_sha256: Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
    end_output_positions: dict[Identity, MemoryOutputPosition] = Field(default_factory=dict)

    @model_validator(mode="after")
    def check_range(self):
        if len(set(self.entry_ids)) != len(self.entry_ids):
            raise ValueError("Source IDs must be unique")
        if self.start.entry_id != self.entry_ids[0] or self.end.entry_id != self.entry_ids[-1]:
            raise ValueError("Source cursors must identify the bounded source range")
        if len(self.entry_ids) == 1 and self.end.offset < self.start.offset:
            raise ValueError("Source cursor cannot move backwards")
        if not set(self.end_output_positions) <= set(self.entry_ids):
            raise ValueError("Output cursor must identify a source in the batch")
        ending = self.end_output_positions.get(self.end.entry_id)
        if ending is not None and ending.character_offset != self.end.offset:
            raise ValueError("Output cursor differs from the processed source offset")
        return self


class MemoryMigrationReceipt(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    format: Literal["legacy-records-v1"]
    source_sha256: Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
    record_ids: Annotated[list[Identity], Field(max_length=10000)]


class MemoryGroupReceipt(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    batch_id: Identity
    proposal_sha256: Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
    canonical_keys: Annotated[list[Identity], Field(min_length=1, max_length=64)]


class MemoryTargetVersion(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    key: Identity
    record_sha256: Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]


class MemoryToolReceipt(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    session_id: Identity
    run_id: Identity
    workspace: Annotated[str, Field(min_length=1, max_length=4096)]
    operation_id: Annotated[str, Field(min_length=1, max_length=4096)]
    call_id: Annotated[str, Field(min_length=1, max_length=256)]
    name: Literal["manage_user_memory", "manage_project_memory"]
    arguments_sha256: Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
    source_leaf_id: Identity
    source_prefix_hash: Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
    revision: Annotated[int, Field(ge=1)]
    record_versions: Annotated[
        dict[Identity, Annotated[str | None, Field(pattern=r"^[a-f0-9]{64}$")]],
        Field(min_length=1, max_length=64),
    ]


class MemoryMoveReceipt(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    source_namespace: tuple[Identity, ...]
    target_namespace: tuple[Identity, ...]
    key: Identity
    session_id: Identity
    action_entry_id: Identity
    source_record_sha256: Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
    target_value_sha256: Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
    side: Literal["source", "target"]


class MemorySnapshot(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    schema_version: Literal[1] = 1
    namespace: tuple[Identity, ...]
    revision: Annotated[int, Field(ge=0)] = 0
    records: dict[Identity, StoredMemory] = Field(default_factory=dict)
    processed_batches: dict[Identity, SourceBatch] = Field(default_factory=dict)
    migration: MemoryMigrationReceipt | None = None
    group_receipts: dict[Identity, MemoryGroupReceipt] = Field(default_factory=dict)
    tool_receipts: dict[Identity, MemoryToolReceipt] = Field(default_factory=dict)
    move_receipts: dict[Identity, MemoryMoveReceipt] = Field(default_factory=dict)

    @model_validator(mode="after")
    def unique_uuid_identities(self):
        for identity, receipt in self.move_receipts.items():
            owner = (
                receipt.source_namespace if receipt.side == "source" else receipt.target_namespace
            )
            if owner != self.namespace or not re.fullmatch(r"[a-f0-9]{64}", identity):
                raise ValueError("Move receipt belongs to another scope or identity")
        identities = [uuid_identity(key) for key in self.records]
        uuid_keys = [identity for identity in identities if identity is not None]
        if len(uuid_keys) != len(set(uuid_keys)):
            raise ValueError("Memory UUID aliases cannot identify duplicate records")
        from pycode.core.summary_source import data_hash

        for identity, receipt in self.tool_receipts.items():
            expected = data_hash(
                {
                    "namespace": self.namespace,
                    "session_id": receipt.session_id,
                    "run_id": receipt.run_id,
                    "operation_id": receipt.operation_id,
                    "call_id": receipt.call_id,
                    "name": receipt.name,
                }
            )
            if identity != expected or receipt.revision > self.revision:
                raise ValueError("Memory tool receipt has a different identity or revision")
        return self
