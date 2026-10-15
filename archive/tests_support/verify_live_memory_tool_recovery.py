"""Real evidence review, committed tool gap and production reopen use isolated memory."""

import asyncio
import hashlib
import json
from dataclasses import asdict
from datetime import datetime, timezone
from pathlib import Path

from archive.scripts.record_verification import manifest

from pycode.adapters.storage.layout import StorageLayout
from pycode.adapters.storage.requests import SQLiteRequestStore
from pycode.app.config import AppConfig, load_providers
from pycode.app.container import build_memory_service, build_session_repository, open_agent_session
from pycode.core.messages import Message, ToolCall
from pycode.core.session import EntryDraft
from pycode.core.tools import ToolContext
from pycode.core.values import plain_data
from pycode.workflows.memory_tools import memory_tools

ROOT = Path(__file__).resolve().parents[2]


async def main():
    run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%f") + "-memory-tool-recovery"
    directory = ROOT / "tests/.runtime-tests/live" / run_id
    workspace = directory / "workspace"
    workspace.mkdir(parents=True)
    selected = load_providers(ROOT / "evals/data/providers-final.json")[0]
    config = AppConfig(
        workspace, directory / "data", credential_workspace=ROOT, allow_delegation=False
    )
    report = {
        "run_id": run_id,
        "model": selected.model_id,
        "thinking": selected.thinking,
        "acceptance": "real_manage_evidence_review_and_production_reopen",
        "source_manifest": manifest(ROOT),
        "checks": {},
        "limitation": (
            "Synthetic predeclared tool call with an omitted JSONL result. "
            "Actual process death is covered by separate subprocess tests."
        ),
    }
    history, memory, session = None, None, None
    ledger = SQLiteRequestStore(StorageLayout(workspace, config.user_data).runtime_database)
    try:
        history = await build_session_repository(config).open(run_id, create=True)
        text = "以后讲解Python函数时，先解释参数从哪里传入。"
        await history.append_message(Message("u", "user", text))
        await history.append_entry(
            EntryDraft(
                "custom",
                run_id="live-run",
                data={
                    "kind": "run_start",
                    "input_id": "u",
                    "execution_session_id": run_id,
                },
            )
        )
        args = {
            "action": "create",
            "content": {
                "kind": "SemanticMemory",
                "content": {
                    "title": "参数讲解偏好",
                    "summary": "先说明参数来源",
                    "body": text,
                    "category": "user_preference",
                    "sources": [
                        {
                            "session_id": "current",
                            "entry_id": "current",
                            "quote": text,
                        }
                    ],
                },
            },
        }
        call = ToolCall("remember-call", "manage_user_memory", args)
        await history.append_message(
            Message(
                "issued",
                "assistant",
                tool_calls=(call,),
                stop_reason="tool_calls",
                provider_metadata={"pycode_run_id": "live-run"},
            )
        )
        memory = build_memory_service(config, history, selected)
        await memory.ensure_store()
        tool = next(t for t in memory_tools(memory) if t.spec.name == call.name)
        result = await tool.execute(
            ToolContext(
                run_id,
                "live-run",
                "live-run:issued:remember-call",
                str(workspace),
                call.id,
                call.name,
            ),
            args,
        )
        key = result.details["memory_ids"][0]
        state = await memory.store.snapshot(memory.user_namespace)
        report["checks"]["real_manage_reliably_committed"] = not result.is_error and bool(
            state["tool_receipts"]
        )
        await memory.edit(key, "以后讲解Python函数时，先给出最小示例。")
        latest = await memory.store.snapshot(memory.user_namespace)
        before = len(await ledger.list_requests(run_id))
        await memory.aclose()
        await history.aclose()
        history = None
        session = await open_agent_session(
            config,
            (selected,),
            run_id,
            refresh_models=False,
            startup_hooks=False,
            connect_mcp=False,
        )
        snapshot = await session._store.snapshot()
        results = [
            entry.message.tool_result
            for entry in snapshot.entries
            if entry.message is not None and entry.message.tool_result is not None
        ]
        report["checks"].update(
            exact_success_result_restored=len(results) == 1
            and not results[0].is_error
            and results[0].call_id == call.id
            and bool(results[0].output_id)
            and results[0].details.get("memory_operation_recovered") is True,
            later_correction_preserved=await session._memory.store.snapshot(
                session._memory.user_namespace
            )
            == latest,
            original_snapshot_restored=len(results) == 1
            and plain_data(results[0].details["memory_records"])
            == plain_data(result.details["memory_records"]),
            recovery_adds_no_model_calls=len(await ledger.list_requests(run_id)) == before,
        )
        report["status"] = "passed" if all(report["checks"].values()) else "failed"
    except Exception as error:
        report.update(
            status="failed",
            error_type=type(error).__name__,
            error_code=getattr(error, "code", None),
        )
    finally:
        if session is not None:
            await session.aclose()
        if memory is not None:
            await memory.aclose()
        if history is not None:
            await history.aclose()
        report["requests"] = [asdict(record) for record in await ledger.list_requests(run_id)]
        await ledger.aclose()
        path = directory / "result.json"
        path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
        print(
            json.dumps(
                {
                    "status": report["status"],
                    "checks": report["checks"],
                    "error_type": report.get("error_type"),
                    "error_code": report.get("error_code"),
                    "request_count": len(report["requests"]),
                    "report": str(path),
                    "report_sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                    "reported_total_tokens": sum(
                        (record.get("usage") or {}).get("total_tokens") or 0
                        for record in report["requests"]
                    ),
                },
                ensure_ascii=False,
            )
        )
    return 0 if report["status"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
