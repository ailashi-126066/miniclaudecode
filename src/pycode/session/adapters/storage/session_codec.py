"""Strict native JSONL boundary; never reads upstream session formats."""

import json

from pydantic import TypeAdapter, ValidationError

from pycode.kernel import SessionEntry, SessionHeader, StorageError, json_dumps, plain_data


def encode_header(header: SessionHeader) -> bytes:
    return encode({"record_type": "header", **plain_data(header)})


def encode_entry(entry: SessionEntry) -> bytes:
    return encode(plain_data(entry))


def encode(data: dict) -> bytes:
    return (
        json_dumps(data, sort_keys=True, ensure_ascii=False, separators=(",", ":")) + "\n"
    ).encode()


def decode_header(data: bytes) -> SessionHeader:
    try:
        payload = json.loads(data)
        if payload.get("record_type") != "header" or payload.get("application") != "pycode":
            raise ValueError("Not a native session header")
        version = payload.get("schema_version")
        if version not in {0, 1} or isinstance(version, bool):
            raise ValueError("Unsupported native session schema")
        payload.pop("record_type")
        payload["schema_version"] = 1
        return TypeAdapter(SessionHeader).validate_json(json_dumps(payload), strict=True)
    except (ValueError, TypeError, ValidationError) as error:
        raise StorageError(
            "invalid_session_header", "Invalid or unsupported native session header"
        ) from error


def decode_entry(data: bytes) -> SessionEntry:
    try:
        entry = TypeAdapter(SessionEntry).validate_json(data, strict=True)
        validate_entry(entry)
        return entry
    except (ValueError, TypeError, ValidationError) as error:
        raise StorageError("invalid_session_entry", "Invalid native session entry") from error


def validate_entry(entry: SessionEntry) -> None:
    if not entry.id or not entry.type or not entry.timestamp:
        raise ValueError("Entry identity, type and timestamp are required")
    if entry.type == "message":
        message = entry.message
        if message is None or not message.id:
            raise ValueError("Completed message requires a stable ID")
        if message.role == "tool" and message.tool_result is None:
            raise ValueError("Tool message requires its paired result")
        if message.role != "tool" and message.tool_result is not None:
            raise ValueError("Only tool messages can contain a tool result")
        if message.role != "assistant" and (message.tool_calls or message.thinking):
            raise ValueError("Only assistant messages can contain thinking or tool calls")
    elif entry.type == "input_queued":
        if (
            entry.message is None
            or entry.message.role != "user"
            or not entry.message.id
            or not entry.run_id
            or entry.data.get("queue_kind") not in ("steer", "follow_up")
            or entry.message.tool_result is not None
            or entry.message.tool_calls
            or entry.message.thinking
        ):
            raise ValueError("Queued input requires identified user, run and queue kind")
    elif entry.type == "model_change":
        if entry.selection is None:
            raise ValueError("Model change requires its selection snapshot")
    elif entry.type == "thinking_level_change":
        if not isinstance(entry.data.get("thinking"), str):
            raise ValueError("Thinking change requires a level")
    elif entry.type == "context_edit":
        if not isinstance(entry.data.get("target_id"), str) or "replacement" not in entry.data:
            raise ValueError("Context edit requires a target and explicit replacement")
        if entry.data["replacement"] is not None and not isinstance(entry.data["replacement"], str):
            raise ValueError("Context replacement must be text or an omission")
    elif entry.type == "compaction":
        if not isinstance(entry.data.get("summary"), str) or not isinstance(
            entry.data.get("first_kept_entry_id"), str
        ):
            raise ValueError("Compaction requires summary and retained boundary")
    elif entry.type == "branch_summary":
        if not isinstance(entry.data.get("summary"), str) or not isinstance(
            entry.data.get("from_id"), str
        ):
            raise ValueError("Branch summary requires source and summary")
    elif entry.type == "custom_message":
        if not isinstance(entry.data.get("text"), str):
            raise ValueError("Custom message requires model-visible text")
