# Source: MewCode local skills/loader.py; SHA-256:
# 5888d912116f3a08cca7d75e54234299d6d2e8aa2789ca42c017d804390cc9b3
# Adaptation: ordered trusted roots, recursive discovery, hashes and no resource imports.
# 来源：公众号@小林coding
# 后端八股网站：xiaolincoding.com
# Agent网站：xiaolinnote.com
# 简历模版：jianli.xiaolinnote.com
"""Read Skills only under explicitly configured roots and expose metadata first."""

import asyncio
import hashlib
import os
import stat
from dataclasses import replace
from pathlib import Path, PurePosixPath

from pycode.adapters.skills.parser import parse_skill
from pycode.adapters.skills.resources import parse_resource_tools
from pycode.core.errors import ToolError
from pycode.core.hashing import data_hash
from pycode.core.skills import SkillIndex

MAX_SKILL_BYTES = 256 * 1024


def linked(path):
    info = path.lstat()
    return stat.S_ISLNK(info.st_mode) or bool(getattr(info, "st_file_attributes", 0) & 0x400)


class FileSkillCatalog:
    def __init__(self, roots: tuple[tuple[str, Path], ...]):
        self._roots = roots

    def virtual_path(self, entry):
        for source, root in self._roots:
            if source == entry.source:
                relative = Path(entry.path).relative_to(root.resolve()).as_posix()
                return "/skills/" + source + "/" + relative
        raise ToolError("skill_not_accessible", "Skill has no trusted virtual mount")

    async def entry_at_virtual_path(self, virtual_path):
        if not virtual_path.endswith("/SKILL.md"):
            return None
        parts = PurePosixPath(virtual_path).parts
        if ".." in parts or "\\" in virtual_path:
            raise ToolError("skill_not_accessible", "Skill path escapes its source")
        for source, root in self._roots:
            prefix = "/skills/" + source + "/"
            if not virtual_path.startswith(prefix):
                continue
            relative = virtual_path.removeprefix(prefix)
            path = root.resolve() / relative

            def selected_document(path=path, source=source):
                return self.document(self.checked(str(path)), source).entry

            return await self.io(selected_document)
        return None

    async def io(self, action):
        owned = asyncio.create_task(asyncio.to_thread(action))
        try:
            return await asyncio.shield(owned)
        except asyncio.CancelledError:
            await owned
            raise
        except OSError as error:
            raise ToolError("skill_io_error", "Cannot read Skill storage") from error

    def document(self, path, source):
        if linked(path) or not path.is_file() or path.stat().st_size > MAX_SKILL_BYTES:
            raise ToolError("invalid_skill", "Skill must be a bounded regular file")
        with path.open("rb") as stream:
            data = stream.read(MAX_SKILL_BYTES + 1)
        if len(data) > MAX_SKILL_BYTES:
            raise ToolError("invalid_skill", "Skill exceeds document size limit")
        try:
            raw = data.decode("utf-8-sig")
        except UnicodeError as error:
            raise ToolError("invalid_skill", "Skill must be UTF-8") from error
        document = parse_skill(
            raw, str(path.resolve()), source, content_hash=hashlib.sha256(data).hexdigest()
        )
        files = self.resource_files(path.parent)
        return replace(
            document,
            entry=replace(document.entry, resource_hash=data_hash(files) if files else None),
        )

    def resource_files(self, directory):
        manifest = directory / "tool.json"
        if not manifest.exists():
            return {}
        candidates = [manifest]
        references = directory / "references"
        if references.exists():
            if linked(references):
                raise ToolError(
                    "invalid_skill_resource", "Linked resource directories are forbidden"
                )
            for parent, names, files in os.walk(references, followlinks=False):
                names[:] = sorted(name for name in names if name != "__pycache__")
                if any(linked(Path(parent) / name) for name in names):
                    raise ToolError(
                        "invalid_skill_resource", "Linked resource directories are forbidden"
                    )
                candidates.extend(
                    Path(parent) / name for name in sorted(files) if name.endswith(".py")
                )
        if len(candidates) > 128:
            raise ToolError("invalid_skill_resource", "Resource implementation count exceeds limit")
        hashes = {}
        for candidate in candidates:
            if (
                linked(candidate)
                or not candidate.is_file()
                or candidate.stat().st_size > 1024 * 1024
            ):
                raise ToolError("invalid_skill_resource", "Resource implementation must be bounded")
            with candidate.open("rb") as stream:
                content = stream.read(1024 * 1024 + 1)
            if len(content) > 1024 * 1024:
                raise ToolError("invalid_skill_resource", "Resource implementation exceeds limit")
            hashes[candidate.relative_to(directory).as_posix()] = hashlib.sha256(
                content
            ).hexdigest()
        return hashes

    async def tools(self, entry):
        def inspect():
            path = self.checked(entry.path)
            files = self.resource_files(path.parent)
            if (data_hash(files) if files else None) != entry.resource_hash:
                raise ToolError(
                    "skill_resources_changed", "Reload before activating changed resources"
                )
            if not files:
                return ()
            raw = (path.parent / "tool.json").read_bytes()
            if hashlib.sha256(raw).hexdigest() != files["tool.json"]:
                raise ToolError(
                    "skill_resources_changed", "Resource declaration changed during load"
                )
            return parse_resource_tools(raw, entry, files)

        return await self.io(inspect)

    async def verify_resources(self, activation):
        def verify():
            # SKILL.md contents may change; the active instructions are already pinned.
            path = self.checked(activation["path"])
            files = self.resource_files(path.parent)
            if data_hash(files) != activation["resource_hash"]:
                raise ToolError(
                    "skill_resources_changed",
                    "Active resource implementation changed or is missing",
                )

        await self.io(verify)

    async def scan(self):
        def scan():
            entries, issues = {}, []
            for source, root in self._roots:
                if not root.exists():
                    continue
                if linked(root):
                    issues.append(source + ": linked root skipped")
                    continue
                for directory, names, files in os.walk(root, followlinks=False):
                    parent = Path(directory)
                    names[:] = sorted(
                        name
                        for name in names
                        if not name.startswith(".") and not linked(parent / name)
                    )
                    if "SKILL.md" not in files:
                        continue
                    path = parent / "SKILL.md"
                    try:
                        document = self.document(path, source)
                    except ToolError as error:
                        issues.append(
                            source + ": " + str(path.relative_to(root)) + ": " + error.code
                        )
                        continue
                    entries[document.entry.name] = document.entry
            return SkillIndex(tuple(entries[name] for name in sorted(entries)), tuple(issues))

        return await self.io(scan)

    async def read(self, entry):
        def read():
            path = self.checked(entry.path)
            document = self.document(path, entry.source)
            if document.entry != entry:
                raise ToolError(
                    "skill_changed", "Skill changed since catalog refresh; reload explicitly"
                )
            return document

        return await self.io(read)

    def checked(self, value):
        path = Path(value)
        if not any(path.is_relative_to(root.resolve()) for _, root in self._roots):
            raise ToolError("skill_not_accessible", "Skill is outside the active trusted roots")
        root = next(
            root.resolve() for _, root in self._roots if path.is_relative_to(root.resolve())
        )
        for parent in (path, *path.parents):
            if parent == root:
                break
            if linked(parent):
                raise ToolError("skill_not_accessible", "Linked Skill paths are not accessible")
        if not path.resolve().is_relative_to(root):
            raise ToolError("skill_not_accessible", "Skill path escapes its trusted root")
        return path

    async def resource(self, entry, relative_path):
        def read():
            relative = Path(relative_path)
            if (
                relative.is_absolute()
                or relative.drive
                or ".." in relative.parts
                or not relative.parts
                or relative.parts[0] not in {"references", "scripts", "assets"}
            ):
                raise ToolError(
                    "invalid_skill_resource",
                    "Resource must be inside references, scripts or assets",
                )
            source = self.checked(entry.path)
            path = self.checked(str(source.parent / relative))
            if not path.is_file() or path.stat().st_size > MAX_SKILL_BYTES:
                raise ToolError(
                    "invalid_skill_resource", "Resource is missing or exceeds the read limit"
                )
            try:
                with path.open("rb") as stream:
                    data = stream.read(MAX_SKILL_BYTES + 1)
                if len(data) > MAX_SKILL_BYTES:
                    raise ToolError("invalid_skill_resource", "Resource exceeds the read limit")
                return data.decode("utf-8")
            except UnicodeError as error:
                raise ToolError("invalid_skill_resource", "Resource is not UTF-8 text") from error

        return await self.io(read)
