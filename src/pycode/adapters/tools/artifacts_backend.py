"""Read-only native file access to branch-scoped, canonical output objects."""

import fnmatch
import re

from deepagents.backends.protocol import (
    BackendProtocol,
    EditResult,
    GlobResult,
    GrepResult,
    LsResult,
    ReadResult,
    WriteResult,
)

from pycode.adapters.tools.output_store import entry_result
from pycode.core.errors import ToolError
from pycode.core.session_tree import SessionTree
from pycode.core.truncation import MAX_BYTES, MAX_LINES


class ToolArtifactsBackend(BackendProtocol):
    def __init__(self, outputs, history):
        self.outputs = outputs
        self.history = history

    def identifier(self, path):
        match = re.fullmatch(r"/(output_[a-f0-9]{32})\.txt", path)
        if match is None:
            raise ToolError("output_not_accessible", "Use a registered output object path")
        return match.group(1)

    def read(self, file_path, offset=0, limit=2000):
        return ReadResult(error="async_backend_required: use the asynchronous Agent entrypoint")

    async def aread(self, file_path, offset=0, limit=2000):
        try:
            identifier = self.identifier(file_path)
            if limit <= 0:
                return ReadResult(
                    file_data={"content": "", "encoding": "utf-8"}, no_lines_requested=True
                )
            page = await self.outputs.read(
                identifier, max(0, offset) + 1, min(limit, MAX_LINES), MAX_BYTES - 512
            )
            text = page.text
            if page.next_byte_offset:
                text += (
                    f"\n[Continue with ReadToolResult: output_id={identifier}, "
                    f"offset={page.next_offset}, byte_offset={page.next_byte_offset}]"
                )
            if not text:
                return ReadResult(file_data={"content": "", "encoding": "utf-8"})
            return ReadResult(
                file_data={"content": text, "encoding": "utf-8"},
                start_line=page.start_line,
                end_line=page.end_line,
                next_offset=page.end_line if page.has_more and not page.next_byte_offset else None,
            )
        except ToolError as error:
            return ReadResult(error=error.code + ": " + str(error))

    def write(self, file_path, content):
        return WriteResult(error="permission_denied: output objects are immutable")

    def edit(self, file_path, old_string, new_string, replace_all=False):
        return EditResult(error="permission_denied: output objects are immutable")

    async def als(self, path):
        if path not in {"/", ""}:
            return LsResult(error="output_not_accessible: list the artifact root")
        snapshot = await self.history.snapshot()
        entries = []
        seen = set()
        for entry in SessionTree(snapshot.index).path(snapshot.leaf_id):
            result = entry_result(entry)
            if result is None or result.output_id is None or result.output_id in seen:
                continue
            seen.add(result.output_id)
            entries.append({"path": "/" + result.output_id + ".txt", "is_dir": False})
        entries.sort(key=lambda item: item["path"])
        return LsResult(entries=entries)

    async def aglob(self, pattern, path=None):
        listing = await self.als(path or "/")
        if listing.error:
            return GlobResult(error=listing.error)
        matches = []
        for entry in listing.entries:
            if fnmatch.fnmatchcase(entry["path"], pattern) or fnmatch.fnmatchcase(
                entry["path"].lstrip("/"), pattern
            ):
                matches.append(entry)
        return GlobResult(matches=matches)

    async def agrep(self, pattern, path=None, glob=None, *, max_count=None):
        try:
            expression = re.compile(pattern)
        except re.error as error:
            return GrepResult(error="invalid_regex: " + str(error))
        listing = await self.als("/")
        matches = []
        ceiling = max_count if max_count is not None else 1000
        for entry in listing.entries:
            filename = entry["path"]
            if path not in {None, "/"} and filename != path:
                continue
            if glob and not fnmatch.fnmatchcase(filename.lstrip("/"), glob):
                continue
            identifier = self.identifier(filename)
            offset, byte_offset = 1, 0
            while True:
                page = await self.outputs.read(
                    identifier, offset, MAX_LINES, MAX_BYTES, byte_offset
                )
                line_number = page.start_line
                for line in page.text.splitlines():
                    if expression.search(line):
                        matches.append({"path": filename, "line": line_number, "text": line[:2048]})
                        if len(matches) >= ceiling:
                            return GrepResult(matches=matches, truncated=True)
                    line_number += 1
                if not page.has_more:
                    break
                offset, byte_offset = page.next_offset, page.next_byte_offset
        return GrepResult(matches=matches)
