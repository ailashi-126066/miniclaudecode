"""Skills types: component-owned implementation."""

from dataclasses import dataclass
from typing import Literal

from pycode.kernel import ToolError


@dataclass(frozen=True)
class SkillEntry:
    name: str
    description: str
    source: str
    path: str
    content_hash: str
    mode: Literal["inline"] = "inline"
    allowed_tools: tuple[str, ...] = ()
    context: Literal["full", "recent", "none"] = "full"
    model: str | None = None
    resource_hash: str | None = None


@dataclass(frozen=True)
class SkillDocument:
    entry: SkillEntry
    raw: str
    body: str


@dataclass(frozen=True)
class SkillIndex:
    entries: tuple[SkillEntry, ...]
    issues: tuple[str, ...] = ()


def skill_entry(data):
    return SkillEntry(
        data["name"],
        data["description"],
        data["source"],
        data["path"],
        data["content_hash"],
        data["mode"],
        tuple(data["allowed_tools"]),
        data["context"],
        data["model"],
        data.get("resource_hash"),
    )


def active_skills(path):
    active = {}
    for entry in path:
        if entry.type == "compaction":
            for item in entry.data.get("checkpoint", {}).get("skills", ()):
                active[item["name"]] = item
        elif entry.type == "skill_load":
            active[entry.data["name"]] = entry.data
        elif entry.type == "fork_context":
            active = {}
    return tuple(active[name] for name in sorted(active))


def skill_scope(path):
    allowed = None
    for item in active_skills(path):
        declared = item.get("allowed_tools", ())
        if declared:
            allowed = set(declared) if allowed is None else allowed.intersection(declared)
    return allowed


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
