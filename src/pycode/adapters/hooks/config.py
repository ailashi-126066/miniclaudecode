# Source: MewCode local hooks/loader.py; SHA-256:
# f70cd9c61d908f98f7666171aae1789f806cdd5bc04ea518f2e6219c01d38914
# Adaptation: strict external types, ordered trusted files and explicit stable IDs.
# 来源：公众号@小林coding
# 后端八股网站：xiaolincoding.com
# Agent网站：xiaolinnote.com
# 简历模版：jianli.xiaolinnote.com
"""Load configuration without running actions or importing project code."""

import json
import re
from dataclasses import fields
from pathlib import Path
from urllib.parse import urlsplit

from pycode.core.errors import DomainError
from pycode.core.hooks import HOOK_EVENTS, Hook, HookAction, parse_condition


def parse_hooks(raw, source="user"):
    if not isinstance(raw, list) or len(raw) > 128:
        raise DomainError("invalid_hook_config", "Hooks must be a bounded list")
    hooks, names = [], set()
    for index, item in enumerate(raw):
        if not isinstance(item, dict):
            raise DomainError("invalid_hook_config", "Hook must be an object")
        event = item.get("event")
        if not isinstance(event, str) or event not in HOOK_EVENTS:
            raise DomainError("invalid_hook_config", "Unknown lifecycle event")
        name = item.get("id", event + "_" + str(index))
        if (
            not isinstance(name, str)
            or not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", name)
            or name in names
        ):
            raise DomainError("invalid_hook_config", "Hook IDs must be unique stable names")
        names.add(name)
        for flag in ("reject", "once", "async"):
            if not isinstance(item.get(flag, False), bool):
                raise DomainError("invalid_hook_config", "Hook flags must be booleans")
        reject, background = item.get("reject", False), item.get("async", False)
        if (reject and event != "pre_tool_use") or (background and event == "pre_tool_use"):
            raise DomainError(
                "invalid_hook_config", "pre_tool_use must be synchronous; only it can reject"
            )
        condition = item.get("if", "")
        if not isinstance(condition, str) or len(condition) > 4096:
            raise DomainError("invalid_hook_config", "Hook condition must be bounded text")
        action = item.get("action")
        if (
            not isinstance(action, dict)
            or not isinstance(action.get("type"), str)
            or action.get("type")
            not in {
                "command",
                "prompt",
                "http",
                "agent",
            }
        ):
            raise DomainError("invalid_hook_config", "Unknown Hook action")
        if not set(action) <= {item.name for item in fields(HookAction)}:
            raise DomainError("invalid_hook_config", "Unknown Hook action field")
        required = {"command": "command", "prompt": "message", "http": "url", "agent": "prompt"}[
            action["type"]
        ]
        if not isinstance(action.get(required), str) or not action[required]:
            raise DomainError("invalid_hook_config", "Hook action is missing required text")
        for key in ("command", "message", "url", "method", "body", "prompt"):
            if not isinstance(action.get(key, ""), str) or len(action.get(key, "")) > 64000:
                raise DomainError("invalid_hook_config", "Hook action text exceeds limit")
        seconds = action.get("timeout", 30)
        if (
            isinstance(seconds, bool)
            or not isinstance(seconds, (int, float))
            or not 0 < seconds <= 600
        ):
            raise DomainError("invalid_hook_config", "Hook timeout must be positive and bounded")
        headers = action.get("headers", {})
        if not isinstance(headers, dict) or any(
            not isinstance(key, str) or not isinstance(value, str) for key, value in headers.items()
        ):
            raise DomainError("invalid_hook_config", "HTTP headers must be text")
        for key, value in headers.items():
            if key.casefold() in {"authorization", "cookie", "x-api-key"} and "$ENV." not in value:
                raise DomainError(
                    "invalid_hook_config", "Authentication headers need environment references"
                )
        if action["type"] == "http":
            try:
                url = urlsplit(action["url"])
                valid = url.port is None or 0 < url.port < 65536
            except ValueError as error:
                raise DomainError("invalid_hook_config", "Invalid Hook URL") from error
            if (
                not valid
                or url.scheme not in {"http", "https"}
                or not url.hostname
                or url.username
                or url.password
            ):
                raise DomainError(
                    "invalid_hook_config", "Hook URL must be HTTP(S) without credentials"
                )
            if action.get("method", "POST") not in {
                "GET",
                "POST",
                "PUT",
                "PATCH",
                "DELETE",
                "HEAD",
            }:
                raise DomainError("invalid_hook_config", "Invalid Hook HTTP method")
        hooks.append(
            Hook(
                name,
                event,
                HookAction(**action),
                parse_condition(condition),
                reject,
                item.get("once", False),
                background,
                source,
            )
        )
    return tuple(hooks)


def load_hooks(roots: tuple[tuple[str, Path], ...]):
    merged = {}
    for source, path in roots:
        if not path.exists():
            continue
        try:
            if not path.is_file() or path.stat().st_size > 1024 * 1024:
                raise ValueError("Hook config exceeds limit")
            raw = json.loads(path.read_text(encoding="utf-8-sig"))
            if isinstance(raw, dict):
                raw = raw["hooks"]
            for hook in parse_hooks(raw, source):
                merged.pop(hook.id, None)
                merged[hook.id] = hook
        except (OSError, ValueError, KeyError) as error:
            raise DomainError("invalid_hook_config", "Cannot read Hook configuration") from error
    return tuple(merged.values())
