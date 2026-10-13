# Source: MewCode local mcp/loading_strategy.py; SHA-256:
# d34db2524575d47b93c7c68fe8b93340bc84d4cc23a21477e86018059763092b
# Adaptation: direct/dispatch, available input budget and immutable server/tool identities.
"""MCP declarations contain references, never resolved credentials."""

from dataclasses import dataclass, field
from typing import Literal

from pycode.core.messages import ToolResult
from pycode.core.summary_source import data_hash
from pycode.core.tools import ToolSpec
from pycode.core.values import json_dumps


@dataclass(frozen=True)
class MCPServer:
    name: str
    transport: Literal["stdio", "http"]
    command: str = ""
    args: tuple[str, ...] = ()
    cwd: str = ""
    url: str = ""
    env: dict[str, str] = field(default_factory=dict)
    headers: dict[str, str] = field(default_factory=dict)
    timeout: float = 30
    enabled: bool = True
    trusted: bool = False
    loading: Literal["auto", "direct", "dispatch"] = "auto"
    tool_modes: dict[str, str] = field(default_factory=dict)


def loading_mode(input_limit, schema_chars):
    return "direct" if schema_chars <= 0 or schema_chars / 2.5 < input_limit * 0.1 else "dispatch"


def tool_name(server, original):
    return "mcp__" + server + "__" + original


def mcp_spec(tool, *, visible=True):
    readonly = tool["trusted"] and tool.get("annotations", {}).get("readOnlyHint") is True
    return ToolSpec(
        tool["name"],
        tool["description"],
        tool["input_schema"],
        category="read" if readonly else "external",
        execution_mode="parallel" if readonly else "sequential",
        source="mcp:" + tool["server"],
        implementation_hash=tool["schema_hash"],
        policy_hash=tool["config_hash"],
        model_visible=visible,
    )


def choose_tools(catalog, input_limit):
    tools = catalog.get("tools", ())
    chars = sum(len(json_dumps(tool["input_schema"], ensure_ascii=False)) for tool in tools)
    automatic = loading_mode(input_limit, chars)
    result = []
    for tool in tools:
        override = tool.get("loading", "auto")
        mode = automatic if override == "auto" else override
        if mode != "off":
            result.append(mcp_spec(tool, visible=mode == "direct"))
    return tuple(sorted(result, key=lambda spec: spec.name))


def active_mcp(path):
    catalog = None
    for entry in path:
        if entry.type == "compaction" and "mcp" in entry.data.get("checkpoint", {}):
            catalog = entry.data["checkpoint"]["mcp"]
        elif entry.type == "mcp_catalog":
            catalog = entry.data
    return catalog


def catalog_hash(catalog):
    return data_hash({key: catalog[key] for key in ("servers", "tools")})


def result_from_mcp(content, status, artifact, *, name, call_id="", declaration=None):
    """Normalize one native MCP result at the durable message boundary."""
    blocks = [{"type": "text", "text": content}] if isinstance(content, str) else content
    text = []
    media = []
    for block in blocks:
        kind = block.get("type", "unknown")
        if kind == "text":
            text.append(block["text"])
        elif kind == "resource" and isinstance(block.get("resource"), dict):
            text.append(block["resource"].get("text", "[binary resource]"))
        else:
            text.append("[" + kind + ": " + block.get("mime_type", "") + "]")
            media.append(block)
    details = {"mcp_content_blocks": media}
    if declaration is not None:
        for key in ("server", "original_name", "schema_hash"):
            details[key] = declaration[key]
    return ToolResult(
        call_id,
        name,
        "\n".join(text),
        is_error=status == "error",
        structured_content=artifact.get("structured_content")
        if isinstance(artifact, dict)
        else None,
        details=details,
    )
