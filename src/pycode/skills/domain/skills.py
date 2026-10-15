"""Skill index metadata, immutable contents and branch activation projection."""

from dataclasses import dataclass
from typing import Literal

from pycode.kernel import ToolSpec


@dataclass(frozen=True)
class SkillEntry:
    name: str
    description: str
    source: str
    path: str
    content_hash: str
    mode: Literal["inline", "fork"] = "inline"
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


def resource_spec(declaration):
    return ToolSpec(
        declaration["name"],
        declaration["description"],
        declaration["input_schema"],
        source="skill:" + declaration["skill_name"],
        implementation_hash=declaration["implementation_hash"],
        approval_details={
            "skill": declaration["skill_name"],
            "skill_document": declaration["skill_path"],
            "script_relative": declaration["relative_path"],
            "script_sha256": declaration["script_hash"],
            "schema_sha256": declaration["schema_hash"],
        },
    )


def skill_tool_specs(skills):
    specs = []
    for skill in skills:
        for tool in skill.get("resource_tools", ()):
            specs.append(resource_spec(tool))
    return tuple(sorted(specs, key=lambda spec: spec.name))
