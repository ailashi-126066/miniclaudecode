"""Validate discovered declarations without depending on a concrete validator."""

from typing import Protocol


class SchemaChecker(Protocol):
    def check_schema(self, schema: dict) -> None: ...
    def validate(self, schema: dict, arguments: dict) -> None: ...
