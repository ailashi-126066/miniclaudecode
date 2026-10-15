"""Opt-in real main-agent manage/search smoke in isolated application storage."""

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
    run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%f") + "-active-memory"
    directory = ROOT / "tests/.runtime-tests/live" / run_id
    workspace = directory / "workspace"
    workspace.mkdir(parents=True)
    provider = load_providers(ROOT / "tests/evals/providers-final.json")[0]
    config = AppConfig(
        workspace, directory / "data", credential_workspace=ROOT, allow_delegation=False
    )
    report = {
        "run_id": run_id,
        "model": provider.model_id,
        "thinking": provider.thinking,
        "acceptance": "real_active_memory_tools",
        "source_manifest": manifest(ROOT),
        "checks": {},
    }
    session = None
    try:
        session = await open_agent_session(
            config,
            (provider,),
            run_id,
            create=True,
            refresh_models=False,
            startup_hooks=False,
            connect_mcp=False,
        )
        result = await session.run(
            Message(
                "active-user",
                "user",
                "请先调用manage_user_memory保存我的长期偏好：讲解Python函数时先解释参数来源。"
                "来源引文使用‘讲解Python函数时先解释参数来源’，session_id和entry_id均用current，由运行时解析。"
                "然后调用search_user_memory搜索‘Python函数参数来源’核对保存结果，最后只回复‘已记住并核对’。"
                "不要调用其他工具，不创建项目记忆。",
            )
        )
        report["checks"]["foreground_complete"] = result.outcome == "completed"
        await session.collect_memory()
        saved = await session.memory_list()
        report["checks"]["record_reliably_saved"] = bool(saved["records"])
        reader = await build_session_repository(config).open(run_id)
        try:
            snapshot = await reader.snapshot()
            results = [
                entry.message.tool_result
                for entry in snapshot.entries
                if entry.message is not None and entry.message.tool_result is not None
            ]
            managed = [result for result in results if result.name == "manage_user_memory"]
            searched = [result for result in results if result.name == "search_user_memory"]
            report["checks"]["official_manage_settled"] = bool(managed) and all(
                not item.is_error for item in managed
            )
            report["checks"]["official_search_settled"] = bool(searched) and all(
                not item.is_error for item in searched
            )
            report["tool_results"] = [
                {
                    "name": item.name,
                    "call_id": item.call_id,
                    "output_id": item.output_id,
                    "is_error": item.is_error,
                    "error_code": item.error_code,
                }
                for item in results
            ]
        finally:
            await reader.aclose()
        report["answer"] = result.final_text
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
        ledger = SQLiteRequestStore(StorageLayout(workspace, config.user_data).runtime_database)
        try:
            report["requests"] = [asdict(record) for record in await ledger.list_requests(run_id)]
        finally:
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
                    "tool_results": report.get("tool_results"),
                    "request_count": len(report["requests"]),
                    "report": str(path),
                },
                ensure_ascii=False,
            )
        )
    return 0 if report["status"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
