"""Opt-in complete session memory smoke with the configured primary provider settings."""

import asyncio
import json
from dataclasses import asdict
from datetime import datetime, timezone
from pathlib import Path

from archive.scripts.record_verification import manifest

from pycode.adapters.storage.layout import StorageLayout
from pycode.adapters.storage.requests import SQLiteRequestStore
from pycode.app.config import AppConfig, load_providers
from pycode.app.container import build_session_repository, open_agent_session
from pycode.core.messages import Message

ROOT = Path(__file__).resolve().parents[2]


async def main():
    run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%f") + "-live-application"
    directory = ROOT / "tests" / ".runtime-tests" / "live" / run_id
    workspace = directory / "workspace"
    workspace.mkdir(parents=True)
    configured = load_providers(ROOT / "tests/evals/providers-final.json")[0]
    config = AppConfig(
        workspace,
        directory / "data",
        credential_workspace=ROOT,
        allow_delegation=False,
    )
    report = {
        "run_id": run_id,
        "acceptance": "real_application_session",
        "model": configured.model_id,
        "thinking": configured.thinking,
        "primary_max_output_tokens": configured.max_output_tokens,
        "source_manifest": manifest(ROOT),
        "checks": {},
        "limitation": "One session memory smoke; not the final quality or recovery evaluation.",
    }
    session = None
    second_session = None
    second_id = run_id + "-next"
    try:
        session = await open_agent_session(
            config,
            (configured,),
            run_id,
            create=True,
            refresh_models=False,
            startup_hooks=False,
            connect_mcp=False,
        )
        outcome = await session.run(
            Message(
                "live-user",
                "user",
                "长期偏好：讲解代码时，请先解释参数的来源与传递过程。此消息只需回复OK，不调用工具。",
            )
        )
        report["checks"]["foreground_complete"] = outcome.outcome == "completed"
        await session.collect_memory()
        saved = await session.memory_list()
        report["checks"]["background_saved_preference"] = bool(saved["records"])
        report["memory_records"] = saved["records"]
        if saved["records"]:
            second_session = await open_agent_session(
                config,
                (configured,),
                second_id,
                create=True,
                refresh_models=False,
                startup_hooks=False,
                connect_mcp=False,
            )
            second = await second_session.run(
                Message(
                    "live-user-second",
                    "user",
                    "我希望你讲解代码时注意什么？只需一句话回复，不调用工具。",
                )
            )
            report["checks"]["separate_session_foreground_complete"] = second.outcome == "completed"
            report["subsequent_answer"] = second.final_text
            await second_session.collect_memory()
            reader = await build_session_repository(config).open(second_id)
            try:
                snapshot = await reader.snapshot()
                loads = [entry for entry in snapshot.entries if entry.type == "memory_load"]
                loaded = loads[-1].data["records"] if loads else ()
                report["checks"]["separate_session_memory_loaded"] = bool(loaded) and bool(
                    {record["memory_id"] for record in loaded}
                    & {record["memory_id"] for record in saved["records"]}
                )
            finally:
                await reader.aclose()
        report["status"] = "passed" if all(report["checks"].values()) else "failed"
    except Exception as error:
        report["status"] = "failed"
        report["error_type"] = type(error).__name__
        report["error_code"] = getattr(error, "code", None)
    finally:
        if second_session is not None:
            await second_session.aclose()
        if session is not None:
            await session.aclose()
        ledger = SQLiteRequestStore(StorageLayout(workspace, config.user_data).runtime_database)
        try:
            report["requests"] = [asdict(record) for record in await ledger.list_requests(run_id)]
            report["requests"].extend(
                asdict(record) for record in await ledger.list_requests(second_id)
            )
        finally:
            await ledger.aclose()
        report["finished_at"] = datetime.now(timezone.utc).isoformat()
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
                    "request_statuses": [
                        {
                            "purpose": record["purpose"],
                            "status": record["status"],
                            "error_code": record["error_code"],
                        }
                        for record in report["requests"]
                    ],
                    "report": str(path),
                },
                ensure_ascii=False,
            )
        )
    return 0 if report["status"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
