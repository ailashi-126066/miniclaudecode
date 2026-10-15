"""Read-only, version-pinned Skill files for Deep Agents SkillsMiddleware."""

import hashlib

import yaml
from deepagents.backends.protocol import FileDownloadResponse, ReadResult

from pycode.adapters.storage.path_io import file_sha256
from pycode.adapters.tools.backend import PycodeBackend
from pycode.core.errors import ToolError


class SkillBackend(PycodeBackend):
    def __init__(self, root, *, selection=None, prefix=""):
        super().__init__(root, read_only=True)
        self.selection = selection
        self.prefix = prefix

    def ls(self, path):
        result = super().ls(path)
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
        return super().read(file_path, offset, limit)

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
