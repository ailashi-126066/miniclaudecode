"""Native read-only access to original active-branch JSONL entry references."""

import re

from deepagents.backends.protocol import (
    BackendProtocol,
    EditResult,
    LsResult,
    ReadResult,
    WriteResult,
)

from pycode.core.session_tree import SessionTree
from pycode.core.values import json_dumps


class ConversationHistoryBackend(BackendProtocol):
    def __init__(self, history):
        self.history = history

    def read(self, file_path, offset=0, limit=2000):
        return ReadResult(error="async_backend_required: use the async Agent entrypoint")

    async def aread(self, file_path, offset=0, limit=2000):
        match = re.fullmatch(r"/([A-Za-z0-9:_-]+)\.json", file_path)
        if match is None:
            return ReadResult(error="history_not_accessible: use a listed entry ID")
        snapshot = await self.history.snapshot()
        selected = None
        for entry in SessionTree(snapshot.index).path(snapshot.leaf_id):
            if entry.id == match.group(1):
                selected = entry
                break
        if selected is None:
            return ReadResult(error="history_not_accessible: entry is outside the current branch")
        text = json_dumps(selected, ensure_ascii=False, indent=2)
        lines = text.split("\n")
        offset = max(0, offset)
        limit = min(2000, max(0, limit))
        pieces = []
        size = 0
        for line in lines[offset : offset + limit]:
            encoded = (line + "\n").encode()
            if size + len(encoded) > 49500:
                pieces.append(
                    "[History range limited; inspect the original session or ReadToolResult.]\n"
                )
                break
            size += len(encoded)
            pieces.append(line + "\n")
        return ReadResult(file_data={"content": "".join(pieces), "encoding": "utf-8"})

    async def als(self, path):
        if path != "/":
            return LsResult(error="history_not_accessible: list the history root")
        snapshot = await self.history.snapshot()
        entries = []
        for entry in SessionTree(snapshot.index).path(snapshot.leaf_id):
            entries.append({"path": "/" + entry.id + ".json", "is_dir": False})
        return LsResult(entries=entries)

    def write(self, file_path, content):
        return WriteResult(error="permission_denied: history is append-only")

    def edit(self, file_path, old_string, new_string, replace_all=False):
        return EditResult(error="permission_denied: history is append-only")
