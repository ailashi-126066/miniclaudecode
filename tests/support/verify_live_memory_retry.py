"""Opt-in real provider recovery of a partial batch, isolated from user memory."""

import asyncio
import hashlib
import json
from dataclasses import asdict
from datetime import datetime, timezone
from pathlib import Path

from tools.record_verification import manifest

from pycode.adapters.storage.layout import StorageLayout
from pycode.adapters.storage.requests import SQLiteRequestStore
from pycode.app.config import AppConfig, load_providers
from pycode.app.container import build_memory_service, build_session_repository
from pycode.core.memory_jobs import memory_job
from pycode.core.messages import Message
from pycode.services.memory_commit import observed_targets

ROOT = Path(__file__).resolve().parents[2]


def proposal(session_id, entry, quote, body):
    return {
        "kind": "SemanticMemory",
        "content": {
            "title": "代码偏好",
            "summary": body,
            "body": body,
            "category": "user_preference",
            "sources": [{"session_id": session_id, "entry_id": entry, "quote": quote}],
        },
    }


async def main():
    run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%f") + "-memory-retry"
    directory = ROOT / "tests/.runtime-tests/live" / run_id
    workspace = directory / "workspace"
    workspace.mkdir(parents=True)
    selected = load_providers(ROOT / "tests/evals/providers-final.json")[0]
    config = AppConfig(
        workspace, directory / "data", credential_workspace=ROOT, allow_delegation=False
    )
    report = {
        "run_id": run_id,
        "model": selected.model_id,
        "thinking": selected.thinking,
        "acceptance": "real_partial_memory_reorganization",
        "source_manifest": manifest(ROOT),
        "checks": {},
        "limitation": "A synthetic recovery smoke; not a full quality or rule-effect evaluation.",
    }
    history, service = None, None
    try:
        history = await build_session_repository(config).open(run_id, create=True)
        first = "以后代码示例优先使用Python。"
        second = "讲解函数时先解释参数从哪里传入，再解释用途。"
        entry = await history.append_message(Message("u", "user", first + second))
        service = build_memory_service(config, history, selected)
        await service.ensure_store()
        namespace = service.user_namespace
        snapshot = await history.snapshot()
        bad_body = "每次讲解必须给出完整项目与所有测试，并逐行解释全部代码。"
        values = {
            "completed-preference": proposal(run_id, entry, first, first),
            "unfinished-preference": proposal(run_id, entry, second, bad_body),
        }
        job = memory_job(
            namespace,
            snapshot,
            [entry],
            {entry: (0, len(first + second))},
            run_id,
            service.timing.now(),
        )
        job = await service.jobs.create(job)
        job = await service.jobs.update(job, now=service.timing.now(), status="running")
        job = await service.jobs.update(
            job,
            now=service.timing.now(),
            status="proposed",
            proposals=values,
            observed_revision=0,
            observed_targets=observed_targets(values, {}),
        )
        initial_complete = await service.process(job)
        state = await service.store.snapshot(namespace)
        pending = await service.jobs.get(namespace, job.job_id)
        report["checks"]["unsupported_group_left_pending"] = (
            not initial_complete
            and set(state["records"]) == {"completed-preference"}
            and not state["processed_batches"]
            and pending.reorganize_reason == "memory_claim_unsupported"
        )
        if not report["checks"]["unsupported_group_left_pending"]:
            raise RuntimeError("initial_partial_batch_not_observed")
        original = state["records"]["completed-preference"]
        await service.aclose()
        service = build_memory_service(config, history, selected)
        await service.ensure_store()
        complete = await service.process(await service.jobs.get(namespace, job.job_id))
        state = await service.store.snapshot(namespace)
        bodies = [record["value"]["content"]["body"] for record in state["records"].values()]
        report["checks"].update(
            completed_group_preserved=state["records"].get("completed-preference") == original,
            failed_claim_removed=all(bad_body not in body for body in bodies),
            corrected_preference_saved=any(
                "参数" in body and ("传入" in body or "来源" in body) for body in bodies
            ),
            durable_progress_and_job_completed=complete
            and job.job_id in state["processed_batches"]
            and (await service.jobs.get(namespace, job.job_id)).status == "completed",
        )
        report["records"] = state["records"]
        report["group_receipt_count"] = len(state["group_receipts"])
        report["status"] = "passed" if all(report["checks"].values()) else "failed"
    except Exception as error:
        report.update(
            status="failed",
            error_type=type(error).__name__,
            error_code=getattr(error, "code", None),
        )
    finally:
        if service is not None:
            await service.aclose()
        if history is not None:
            await history.aclose()
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
