"""Prompt-injection hints for untrusted MCP tool results."""
from __future__ import annotations

import re
from dataclasses import dataclass


@dataclass(frozen=True)
class InjectionFinding:
    rule: str
    excerpt: str


_INJECTION_PATTERNS = (
    (re.compile(r"ignore\s+(all\s+)?(previous|prior)\s+instructions", re.I), "instruction-override"),
    (re.compile(r"(system|developer)\s+(prompt|message)", re.I), "privileged-message"),
    (re.compile(r"(reveal|print|show)\s+(your\s+)?(system\s+)?prompt", re.I), "prompt-exfiltration"),
    (re.compile(r"do\s+not\s+trust\s+(the\s+)?user", re.I), "authority-confusion"),
)


def scan_prompt_injection(text: str) -> InjectionFinding | None:
    for pattern, rule in _INJECTION_PATTERNS:
        match = pattern.search(text)
        if match:
            start = max(0, match.start() - 45)
            end = min(len(text), match.end() + 90)
            return InjectionFinding(rule=rule, excerpt=text[start:end].replace("\n", " "))
    return None
