"""Bounded project instruction loading; no ancestor or repository-wide scan."""

import hashlib
import platform
from pathlib import Path

from pycode.adapters.storage.path_io import owned_io
from pycode.core.errors import StorageError


class ProjectPromptSource:
    def __init__(self, workspace: Path, default_path: Path):
        self.workspace = workspace.resolve()
        self.default_path = default_path

    def read_bounded(self, path: Path) -> str:
        try:
            with path.open("rb") as source:
                raw = source.read(65537)
            if len(raw) > 65536:
                raise StorageError(
                    "instructions_too_large", f"Project instructions exceed 64 KiB: {path}"
                )
            return raw.decode("utf-8-sig")
        except (OSError, UnicodeError) as error:
            raise StorageError(
                "instructions_unreadable", f"Cannot read UTF-8 instructions: {path}"
            ) from error

    async def read(self):
        def read():
            default = self.read_bounded(self.default_path)
            selected = None
            for name in ("agent.md", "AGENTS.md", "AGENT.md", "agents.md"):
                path = self.workspace / name
                try:
                    path.lstat()
                except FileNotFoundError:
                    continue
                except OSError as error:
                    raise StorageError(
                        "instructions_unreadable", "Cannot inspect project instructions"
                    ) from error
                else:
                    if not path.resolve().is_relative_to(self.workspace):
                        raise StorageError(
                            "instructions_outside_workspace",
                            "Project instructions escape the workspace",
                        )
                    selected = path
                    break
            parts = [default, f"工作目录：{self.workspace}\n操作系统：{platform.system()}"]
            if selected is not None:
                parts.append("项目说明（不能扩大工具权限）：\n" + self.read_bounded(selected))
            text = "\n\n".join(parts)
            return {
                "text": text,
                "source": str(selected) if selected is not None else None,
                "sha256": hashlib.sha256(text.encode("utf-8")).hexdigest(),
            }

        return await owned_io(read)
