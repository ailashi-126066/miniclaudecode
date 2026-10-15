"""Explicit scope relocation keeps one checked source and a recoverable transfer identity."""

from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

from pycode.core.hashing import data_hash
from pycode.core.memory_schema import Identity, StoredMemory, validate_memory_value
from pycode.core.values import plain_data


class MemoryMove(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    move_id: Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
    source_namespace: tuple[Identity, ...]
    target_namespace: tuple[Identity, ...]
    key: Identity
    record: StoredMemory
    content: Annotated[str | None, Field(max_length=16000)] = None
    session_id: Identity
    workspace: Annotated[str, Field(min_length=1, max_length=4096)]
    source_leaf_id: Identity
    source_prefix_hash: Annotated[str, Field(pattern=r"^[a-f0-9]{64}$")]
    action_entry_id: Identity
    status: Literal["prepared", "target_saved", "completed", "conflict"] = "prepared"
    error_code: Identity | None = None

    def identity(self):
        return self.model_dump(mode="json", exclude={"move_id", "status", "error_code"})

    @model_validator(mode="after")
    def valid(self):
        if (
            self.source_namespace == self.target_namespace
            or self.move_id != data_hash(self.identity())
            or self.content is not None
            and not self.content.strip()
        ):
            raise ValueError("Memory move identity or content is invalid")
        return self


def moved_value(move, target_scope):
    value = plain_data(move.model_dump(mode="json")["record"]["value"])
    content = value["content"]
    if move.content is not None:
        content.update(body=move.content, summary=move.content[:160])
        if value["kind"] == "EpisodicMemory":
            content["verification"] = "unverified"
    if value["kind"] == "ProceduralMemory":
        content["origin"] = "user_explicit"
    if value["kind"] == "SemanticMemory" and (
        content["category"] == "user_preference"
        and target_scope == "project"
        or content["category"] == "project_knowledge"
        and target_scope == "user"
    ):
        # Explicit relocation corrects applicability; it does not assert a new observed fact.
        content["category"] = "correction_feedback"
    if len(content["sources"]) < 32:
        source = {
            "session_id": move.session_id,
            "entry_id": move.action_entry_id,
            "quote": content["body"],
        }
        if source not in content["sources"]:
            content["sources"].append(source)
    return validate_memory_value(value)
