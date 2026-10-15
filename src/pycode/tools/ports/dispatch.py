"""Resolve a declared generic dispatch tool to its underlying native call."""

from typing import Protocol

from pycode.kernel import ToolCall


class ToolDispatch(Protocol):
    def resolve(self, call: ToolCall) -> ToolCall: ...
