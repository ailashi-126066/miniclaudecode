# Source: https://github.com/earendil-works/pi/blob/42a3497d03ad17e308a2299fa824727894f2c0ec/packages/coding-agent/src/core/session-manager.ts
# Adaptation: preserve parent traversal; reject corrupt parent chains and unknown selected leaves.
"""Pure append-order tree validation and explicit root-to-leaf traversal."""

from collections.abc import Mapping

from pycode.kernel.domain.history import SessionEntry
from pycode.kernel.domain.values import freeze_value


class SessionTree:
    def __init__(self, entries):
        if isinstance(entries, Mapping):
            # SessionStore already validated this insertion-ordered index.
            self._index = entries
            return
        self._index = {}
        for entry in entries:
            if not entry.id or entry.id in self._index:
                raise ValueError("Session entry ID is empty or duplicated")
            if entry.parent_id is not None and entry.parent_id not in self._index:
                raise ValueError(f"Session parent must precede its child: {entry.id}")
            self._index[entry.id] = freeze_value(entry)

    def path(self, leaf_id: str | None) -> tuple[SessionEntry, ...]:
        if leaf_id is None:
            return ()
        if leaf_id not in self._index:
            raise ValueError(f"Unknown session leaf: {leaf_id}")
        path = []
        current = self._index[leaf_id]
        while True:
            path.append(current)
            if current.parent_id is None:
                break
            current = self._index[current.parent_id]
        path.reverse()
        return tuple(path)

    def get(self, entry_id: str) -> SessionEntry:
        entry = self._index.get(entry_id)
        if entry is None:
            raise ValueError(f"Unknown session entry: {entry_id}")
        return entry

    def children(self, parent_id):
        children = []
        for entry in self._index.values():
            if entry.parent_id == parent_id:
                children.append(entry)
        return tuple(children)
