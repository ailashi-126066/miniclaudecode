# Source: MewCode local skills/directory.py; SHA-256:
# 2b04c95e8b75a23b8f65990b846e2ac76d08aaf5d18dadf55d76e0f7ff6b500f
# Adaptation: inspect bytes without import, explicit schema errors and immutable resource hashes.
# 来源：公众号@小林coding
# 后端八股网站：xiaolincoding.com
# Agent网站：xiaolinnote.com
# 简历模版：jianli.xiaolinnote.com
"""Validate resource declarations without running any resource Python."""

import json
import re

from pycode.adapters.tools.schema import JsonSchemaValidator
from pycode.core.errors import ToolError
from pycode.core.summary_source import data_hash


def parse_resource_tools(raw, entry, files):
    try:
        declarations = json.loads(raw)
    except (ValueError, UnicodeError, RecursionError) as error:
        raise ToolError("invalid_skill_tools", "tool.json must contain valid JSON") from error
    if isinstance(declarations, dict):
        declarations = [declarations]
    if not isinstance(declarations, list) or len(declarations) > 32:
        raise ToolError("invalid_skill_tools", "tool.json must contain a bounded list of tools")
    tools, names = [], set()
    validator = JsonSchemaValidator()
    for declaration in declarations:
        if not isinstance(declaration, dict):
            raise ToolError("invalid_skill_tools", "Tool declaration must be an object")
        name, description = declaration.get("name"), declaration.get("description")
        if not isinstance(name, str) or not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_-]{0,63}", name):
            raise ToolError("invalid_skill_tools", "Invalid resource tool name")
        if name in names:
            raise ToolError("tool_name_conflict", "Duplicate resource tool name: " + name)
        names.add(name)
        if not isinstance(description, str) or not description.strip() or len(description) > 4096:
            raise ToolError("invalid_skill_tools", "Tool description must be bounded text")
        if "parameters" in declaration and "input_schema" in declaration:
            raise ToolError("invalid_skill_tools", "Declare only one resource input schema")
        schema = declaration.get("parameters", declaration.get("input_schema"))
        if not isinstance(schema, dict) or schema.get("type") != "object":
            raise ToolError("invalid_skill_tools", "Resource input schema must describe an object")
        validator.check_schema(schema)
        relative = "references/" + name + ".py"
        implementation = files.get(relative)
        if implementation is None:
            raise ToolError(
                "skill_implementation_missing", "Missing resource implementation: " + relative
            )
        tools.append(
            {
                "name": name,
                "description": description,
                "input_schema": schema,
                "skill_name": entry.name,
                "skill_path": entry.path,
                "relative_path": relative,
                "script_hash": implementation,
                "schema_hash": data_hash(schema),
                "implementation_hash": data_hash(
                    {"resource_hash": entry.resource_hash, "name": name}
                ),
            }
        )
    return tuple(sorted(tools, key=lambda tool: tool["name"]))
