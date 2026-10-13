"""Bound the visible catalogue without loading or activating Skill bodies."""

from dataclasses import dataclass

from pycode.core.errors import ToolError


@dataclass
class SkillSelection:
    dispatch: bool
    allowed_paths: tuple[str, ...]
    paths: tuple[str, ...] = ()
    revision: int = 0

    def select(self, paths):
        selected = []
        for path in paths:
            if path not in self.allowed_paths:
                raise ToolError(
                    "skill_catalog_changed", "Start a new run to use newly installed Skills"
                )
            if path not in selected:
                selected.append(path)
        selected = selected[:20]
        current = tuple(selected)
        if current != self.paths:
            self.paths = current
            self.revision += 1

    def visible(self, directory):
        if not self.dispatch:
            return True
        prefix = directory.rstrip("/") + "/"
        for path in self.paths:
            if path.startswith(prefix):
                return True
        return False
