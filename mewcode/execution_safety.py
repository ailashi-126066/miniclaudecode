"""Execution-time safeguards shared by interactive and non-interactive Agent runs."""

from __future__ import annotations

import hashlib
import json
import re
import threading
import time
import uuid
from dataclasses import asdict, dataclass
from enum import StrEnum
from pathlib import Path
from typing import Any

from mewcode.tools.diff import build_diff


class RiskLevel(StrEnum):
    LOW = "low"
    MEDIUM = "medium"
    HIGH = "high"
    CRITICAL = "critical"


def classify_risk(tool_name: str, category: str, arguments: dict[str, Any]) -> RiskLevel:
    if tool_name in {"Bash", "mcp_call"}:
        command = str(arguments.get("command", ""))
        if re.search(r"\b(curl|wget|ssh|git\s+push|npm\s+publish)\b", command, re.I):
            return RiskLevel.CRITICAL
        return RiskLevel.HIGH
    if category == "write":
        return RiskLevel.HIGH
    if category == "command":
        return RiskLevel.HIGH
    return RiskLevel.LOW


@dataclass(frozen=True)
class MutationPreview:
    path: str
    before_hash: str
    diff_hash: str
    diff: str

    @classmethod
    def prepare(cls, tool_name: str, arguments: dict[str, Any]) -> "MutationPreview | None":
        if tool_name not in {"WriteFile", "EditFile"}:
            return None
        path = Path(str(arguments.get("file_path", ""))).resolve()
        before = path.read_text(encoding="utf-8") if path.exists() else ""
        if tool_name == "WriteFile":
            after = str(arguments.get("content", ""))
        else:
            old = str(arguments.get("old_string", ""))
            new = str(arguments.get("new_string", ""))
            if before.count(old) != 1:
                raise ValueError("Cannot prepare edit preview: old_string must occur exactly once")
            after = before.replace(old, new, 1)
        diff = build_diff(before, after).text
        return cls(str(path), _sha256(before), _sha256(diff), diff)

    def verify_current(self, tool_name: str, arguments: dict[str, Any]) -> None:
        path = Path(self.path)
        current = path.read_text(encoding="utf-8") if path.exists() else ""
        if _sha256(current) != self.before_hash:
            raise RuntimeError("Mutation target changed after approval; request a new approval")
        if tool_name == "WriteFile":
            after = str(arguments.get("content", ""))
        else:
            old = str(arguments.get("old_string", ""))
            new = str(arguments.get("new_string", ""))
            if current.count(old) != 1:
                raise RuntimeError("Mutation changed after approval; request a new approval")
            after = current.replace(old, new, 1)
        if _sha256(build_diff(current, after).text) != self.diff_hash:
            raise RuntimeError("Mutation diff changed after approval; request a new approval")


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


@dataclass
class ToolExecutionRecord:
    tool_id: str
    tool_name: str
    category: str
    risk: str
    status: str
    args_hash: str
    result: str = ""
    before_hash: str = ""
    diff_hash: str = ""
    run_id: str = ""
    updated_at: float = 0.0


class ExecutionLedger:
    """Append-only JSONL ledger for write/command execution and crash reconciliation."""

    def __init__(self, work_dir: str, session_id: str = "") -> None:
        sid = session_id or "default"
        self.path = Path(work_dir) / ".mewcode" / "sessions" / sid / "tool-ledger.jsonl"
        self.run_id = uuid.uuid4().hex
        self._lock = threading.Lock()
        self._latest: dict[str, ToolExecutionRecord] = {}
        self._load()
        for record in list(self._latest.values()):
            if record.status == "pending" and record.run_id != self.run_id:
                self.record(record, status="unknown", result="Process ended before tool outcome was known")

    def previous(self, tool_id: str) -> ToolExecutionRecord | None:
        return self._latest.get(tool_id)

    def record(
        self,
        record: ToolExecutionRecord,
        *,
        status: str,
        result: str = "",
    ) -> ToolExecutionRecord:
        updated = ToolExecutionRecord(
            **{**asdict(record), "status": status, "result": result, "run_id": self.run_id, "updated_at": time.time()}
        )
        with self._lock:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            with self.path.open("a", encoding="utf-8") as fh:
                fh.write(json.dumps(asdict(updated), ensure_ascii=False) + "\n")
            self._latest[updated.tool_id] = updated
        return updated

    def create(
        self,
        tool_id: str,
        tool_name: str,
        category: str,
        risk: RiskLevel,
        arguments: dict[str, Any],
        preview: MutationPreview | None,
    ) -> ToolExecutionRecord:
        return ToolExecutionRecord(
            tool_id=tool_id,
            tool_name=tool_name,
            category=category,
            risk=risk.value,
            status="new",
            args_hash=_sha256(json.dumps(arguments, sort_keys=True, ensure_ascii=False, default=str)),
            before_hash=preview.before_hash if preview else "",
            diff_hash=preview.diff_hash if preview else "",
        )

    def _load(self) -> None:
        try:
            for line in self.path.read_text(encoding="utf-8").splitlines():
                raw = json.loads(line)
                record = ToolExecutionRecord(**raw)
                self._latest[record.tool_id] = record
        except (OSError, ValueError, TypeError):
            return


def _sha256(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()
