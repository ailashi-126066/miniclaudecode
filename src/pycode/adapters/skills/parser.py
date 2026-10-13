# Source: MewCode local skills/parser.py; SHA-256:
# eba23547c0b392cd13211b38d1f45bc71a86c7de21a05b9998a3cd227ca3a11d
# Adaptation: native immutable data, line-delimited frontmatter and strict metadata types.
# 来源：公众号@小林coding
# 后端八股网站：xiaolincoding.com
# Agent网站：xiaolinnote.com
# 简历模版：jianli.xiaolinnote.com
"""Validate external Skill documents once, without importing resource code."""

import hashlib
import re

import yaml

from pycode.core.errors import ToolError
from pycode.core.skills import SkillDocument, SkillEntry


def parse_skill(raw: str, path: str, source: str, *, content_hash=None) -> SkillDocument:
    lines = raw.lstrip().splitlines(keepends=True)
    if not lines or lines[0].strip() != "---":
        raise ToolError("invalid_skill", "Skill requires YAML frontmatter")
    closing = next((index for index in range(1, len(lines)) if lines[index].strip() == "---"), None)
    if closing is None:
        raise ToolError("invalid_skill", "Skill frontmatter is not closed")
    try:
        meta = yaml.safe_load("".join(lines[1:closing]))
    except yaml.YAMLError as error:
        raise ToolError("invalid_skill", "Skill frontmatter is invalid YAML") from error
    if not isinstance(meta, dict):
        raise ToolError("invalid_skill", "Skill frontmatter must be a mapping")
    name, description = meta.get("name"), meta.get("description")
    if (
        not isinstance(name, str)
        or len(name) > 64
        or re.fullmatch(r"[a-z][a-z0-9-]*", name) is None
    ):
        raise ToolError("invalid_skill", "Skill name must be lowercase letters, digits and hyphens")
    if not isinstance(description, str) or not description.strip() or len(description) > 2048:
        raise ToolError("invalid_skill", "Skill description must be a nonempty string")
    mode, context = meta.get("mode", "inline"), meta.get("context", "full")
    if (
        not isinstance(mode, str)
        or not isinstance(context, str)
        or mode not in {"inline", "fork"}
        or context not in {"full", "recent", "none"}
    ):
        raise ToolError("invalid_skill", "Skill mode or context is invalid")
    if "allowedTools" in meta and "allowed-tools" in meta:
        raise ToolError("invalid_skill", "Use a single allowed-tools declaration")
    allowed = meta.get("allowedTools", meta.get("allowed-tools", []))
    if not isinstance(allowed, list) or any(
        not isinstance(item, str) or not item for item in allowed
    ):
        raise ToolError("invalid_skill", "allowed-tools must be a list of tool names")
    model = meta.get("model")
    if model is not None and (not isinstance(model, str) or not model):
        raise ToolError("invalid_skill", "Skill model must be a nonempty string")
    digest = content_hash or hashlib.sha256(raw.encode()).hexdigest()
    entry = SkillEntry(
        name, description, source, path, digest, mode, tuple(allowed), context, model
    )
    return SkillDocument(entry, raw, "".join(lines[closing + 1 :]).lstrip("\n"))
