"""Skill consumers own their minimal session-write and schema capabilities."""

from typing import Protocol

from pycode.kernel import SessionSnapshot


class CatalogWriteGuard(Protocol):
    def can_initialize(self, snapshot: SessionSnapshot) -> bool: ...


class SchemaChecker(Protocol):
    def check_schema(self, schema: dict) -> None: ...
