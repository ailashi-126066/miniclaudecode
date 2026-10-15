"""Deduplicate model-visible memory bodies without changing formal tool history."""

from dataclasses import replace

from pycode.core.values import json_dumps, plain_data

NAMES = frozenset(
    {"search_user_memory", "search_project_memory", "manage_user_memory", "manage_project_memory"}
)


def memory_key(record):
    return tuple(record["namespace"]), record["memory_id"]


def memory_result(message):
    result = message.tool_result
    return result is not None and result.name in NAMES and "memory_records" in result.details


def project_memory(path, messages, initial):
    latest = {}
    for entry in path:
        if entry.type == "compaction":
            records = entry.data.get("checkpoint", {}).get("memories", ())
        elif entry.type == "memory_load":
            records = entry.data["records"]
        elif entry.message is not None and memory_result(entry.message):
            records = entry.message.tool_result.details["memory_records"]
        else:
            records = ()
        for record in records:
            if "namespace" in record and "version" in record:
                latest[memory_key(record)] = record
        if entry.message is not None and entry.message.tool_result is not None:
            result = entry.message.tool_result
            if result.name in NAMES and "memory_deleted" in result.details:
                namespace = tuple(result.details["memory_namespace"])
                for key in result.details["memory_deleted"]:
                    latest[(namespace, key)] = None
    pinned, seen = [], set()
    for record in initial:
        if "namespace" not in record or "version" not in record:
            pinned.append(record)
            continue
        key = memory_key(record)
        current = latest.get(key)
        if current is not None and current["version"] == record["version"]:
            pinned.append(record)
            seen.add((key, record["version"]))
    projected = []
    for message in messages:
        if not memory_result(message):
            projected.append(message)
            continue
        visible = []
        for record in message.tool_result.details["memory_records"]:
            key = memory_key(record)
            identity = key, record["version"]
            current = latest.get(key)
            if current is None or current["version"] != record["version"]:
                visible.append(
                    {
                        "namespace": list(key[0]),
                        "memory_id": key[1],
                        "version": record["version"],
                        "body_status": "superseded",
                    }
                )
            elif identity in seen:
                visible.append(
                    {
                        "namespace": list(key[0]),
                        "memory_id": key[1],
                        "version": record["version"],
                        "body_status": "already_in_context",
                    }
                )
            else:
                visible.append(plain_data(record))
                seen.add(identity)
        payload = visible
        if message.tool_result.name.startswith("manage_"):
            payload = {
                "status": "saved",
                "records": visible,
                "memory_ids": list(message.tool_result.details.get("memory_ids", ())),
                "deleted_ids": list(message.tool_result.details.get("memory_deleted", ())),
            }
        result = replace(
            message.tool_result,
            text=json_dumps(payload, ensure_ascii=False),
            structured_content=None,
        )
        projected.append(replace(message, tool_result=result))
    return tuple(projected), tuple(pinned)
