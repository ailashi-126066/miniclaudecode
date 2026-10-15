"""Read-only, version-pinned Skill files for Deep Agents SkillsMiddleware."""

import hashlib
from pathlib import Path
from typing import Protocol

import yaml
from deepagents.backends.protocol import (
    BackendProtocol,
    EditResult,
    FileDownloadResponse,
    FileUploadResponse,
    GlobResult,
    GrepResult,
    LsResult,
    ReadResult,
    WriteResult,
)

from pycode.kernel import ToolError, file_sha256, owned_io


class BackendOperations(Protocol):
    receipts: dict[str, str]

    def resolve(self, value: str) -> Path: ...
    def error(self, error: Exception) -> str: ...
    def ls(self, path: str) -> LsResult: ...
    def read(self, file_path: str, offset=0, limit=2000) -> ReadResult: ...
    def grep(self, pattern, path=None, glob=None, *, max_count=None) -> GrepResult: ...
    def glob(self, pattern, path=None) -> GlobResult: ...
    async def agrep(self, pattern, path=None, glob=None, *, max_count=None) -> GrepResult: ...
    async def aglob(self, pattern, path=None) -> GlobResult: ...


class SkillBackend(BackendProtocol):
    def __init__(self, backend: BackendOperations, *, selection=None, prefix=""):
        self.backend = backend
        self.receipts = backend.receipts
        self.read_only = True
        self.selection = selection
        self.prefix = prefix

    def ls(self, path):
        result = self.backend.ls(path)
        if self.selection is None or not self.selection.dispatch or result.error:
            return result
        filtered = []
        for entry in result.entries:
            virtual = self.prefix.rstrip("/") + entry["path"]
            if self.selection.visible(virtual):
                filtered.append(entry)
        result.entries = filtered
        return result

    def verify_version(self, value):
        path = self.resolve(value)
        expected = self.receipts.get(str(path))
        if expected is not None and file_sha256(path) != expected:
            raise ToolError(
                "skill_changed",
                "Skill changed during this run; start a new run to use the new version",
            )
        return path

    def read(self, file_path, offset=0, limit=2000):
        try:
            self.verify_version(file_path)
        except (OSError, ToolError) as error:
            return ReadResult(error=self.error(error))
        return self.backend.read(file_path, offset, limit)

    def download_files(self, paths):
        results = []
        for value in paths:
            try:
                path = self.verify_version(value)
                with path.open("rb") as source:
                    data = source.read(256 * 1024 + 1)
                if len(data) > 256 * 1024:
                    raise ToolError("skill_too_large", "Skill file exceeds 256 KiB")
                self.receipts[str(path)] = hashlib.sha256(data).hexdigest()
                # This names a lazy resolver group; it does not load scripts or
                # expose Python schemas before the framework selects the skill.
                if path.name == "SKILL.md" and (path.parent / "tool.json").is_file():
                    text = data.decode("utf-8-sig")
                    parts = text.split("---", 2)
                    if len(parts) == 3 and not parts[0].strip():
                        frontmatter = yaml.safe_load(parts[1])
                        if not isinstance(frontmatter, dict):
                            raise ToolError("invalid_skill", "Skill frontmatter must be a mapping")
                        metadata = frontmatter.get("metadata", {})
                        if not isinstance(metadata, dict):
                            raise ToolError("invalid_skill", "Skill metadata must be a mapping")
                        metadata = dict(metadata)
                        metadata["include_tools"] = "pycode_skill_" + str(frontmatter["name"])
                        frontmatter["metadata"] = metadata
                        data = (
                            "---\n"
                            + yaml.safe_dump(frontmatter, allow_unicode=True, sort_keys=True)
                            + "---"
                            + parts[2]
                        ).encode("utf-8")
                results.append(FileDownloadResponse(path=value, content=data))
            except (OSError, ToolError, UnicodeError, ValueError, KeyError, yaml.YAMLError):
                results.append(FileDownloadResponse(path=value, error="permission_denied"))
        return results

    def resolve(self, value):
        return self.backend.resolve(value)

    def error(self, error):
        return self.backend.error(error)

    async def als(self, path):
        return await owned_io(self.ls, path)

    async def aread(self, file_path, offset=0, limit=2000):
        return await owned_io(self.read, file_path, offset, limit)

    def grep(self, pattern, path=None, glob=None, *, max_count=None):
        return self.backend.grep(pattern, path, glob, max_count=max_count)

    async def agrep(self, pattern, path=None, glob=None, *, max_count=None):
        return await self.backend.agrep(pattern, path, glob, max_count=max_count)

    def glob(self, pattern, path=None):
        return self.backend.glob(pattern, path)

    async def aglob(self, pattern, path=None):
        return await self.backend.aglob(pattern, path)

    async def adownload_files(self, paths):
        return await owned_io(self.download_files, paths)

    def write(self, file_path, content):
        return WriteResult(error="permission_denied: This backend is read only")

    async def awrite(self, file_path, content):
        return self.write(file_path, content)

    def edit(self, file_path, old_string, new_string, replace_all=False):
        return EditResult(error="permission_denied: This backend is read only")

    async def aedit(self, file_path, old_string, new_string, replace_all=False):
        return self.edit(file_path, old_string, new_string, replace_all)

    def upload_files(self, files):
        return [FileUploadResponse(path=path, error="permission_denied") for path, _ in files]

    async def aupload_files(self, files):
        return self.upload_files(files)
