"""Real explicit feedback creates a rule which alone drives a fresh-session explanation."""

import argparse
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
from pycode.app.container import build_memory_service, build_session_repository, open_agent_session
from pycode.core.messages import Message

ROOT = Path(__file__).resolve().parents[2]


async def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--resume-directory", type=Path)
    args = parser.parse_args()
    timestamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%f")
    source_report = None
    if args.resume_directory is not None:
        directory = args.resume_directory.resolve()
        if not directory.is_relative_to((ROOT / "tests/.runtime-tests/live").resolve()):
            raise ValueError("Resume only isolated live-test storage")
        source_report = directory / "result.json"
        run_id = json.loads(source_report.read_text())["run_id"]
    else:
        run_id = timestamp + "-memory-behavior"
        directory = ROOT / "tests/.runtime-tests/live" / run_id
    workspace = directory / "workspace"
    workspace.mkdir(parents=True, exist_ok=args.resume_directory is not None)
    selected = load_providers(ROOT / "tests/evals/providers-final.json")[0]
    config = AppConfig(
        workspace, directory / "data", credential_workspace=ROOT, allow_delegation=False
    )
    report = {
        "run_id": run_id,
        "model": selected.model_id,
        "thinking": selected.thinking,
        "acceptance": "real_explicit_behavior_rule_application",
        "source_manifest": manifest(ROOT),
        "checks": {},
        "limitation": (
            "Explicit user feedback and fresh-session application smoke. "
            "Not a causal quality comparison or validation of model-inferred rules."
        ),
    }
    if source_report is not None:
        report["recovery_from"] = {
            "report": str(source_report),
            "sha256": hashlib.sha256(source_report.read_bytes()).hexdigest(),
        }
    history, service, session = None, None, None
    ids = [run_id, run_id + "-next"]
    try:
        history = await build_session_repository(config).open(run_id, create=source_report is None)
        text = (
            "以后讲解Python函数时，请始终先说明参数从调用方哪里传入，再说明参数用途。"
            "只把这条长期讲解规则用于代码讲解。"
        )
        entry = (
            next(
                entry.id
                for entry in (await history.snapshot()).entries
                if entry.message is not None and entry.message.id == "feedback"
            )
            if source_report is not None
            else await history.append_message(Message("feedback", "user", text))
        )
        service = build_memory_service(config, history, selected)
        from pycode.app.background_models import ConfiguredMemoryUpdater

        class ObservedUpdater(ConfiguredMemoryUpdater):
            def facade(self, owner, prefix):
                model = super().facade(owner, prefix)
                prior = model.response_validator

                def observe(response):
                    if prior is not None:
                        prior(response)
                    report.setdefault("component_responses", []).append(
                        {
                            "content": response.content,
                            "tool_calls": response.tool_calls,
                        }
                    )

                return model.model_copy(update={"response_validator": observe})

        service.updater = ObservedUpdater(
            selected, service.updater.model_factory, service.updater.store_factory
        )
        await service.initialize()
        if source_report is None:
            await service.settled("feedback-run")
        await service.collect()
        state = await service.store.snapshot(service.user_namespace)
        rules = {
            key: record
            for key, record in state["records"].items()
            if record["value"]["kind"] == "ProceduralMemory"
        }
        report["checks"]["explicit_rule_reliably_saved"] = bool(rules) and all(
            record["value"]["content"]["origin"] == "user_explicit" for record in rules.values()
        )
        report["checks"]["original_feedback_provenance"] = bool(rules) and all(
            any(
                source["session_id"] == run_id and source["entry_id"] == entry
                for source in record["value"]["content"]["sources"]
            )
            for record in rules.values()
        )
        report["checks"]["feedback_batch_completed"] = bool(
            state["processed_batches"]
        ) and not await service.jobs.pending(service.user_namespace, run_id, str(workspace))
        if not report["checks"]["explicit_rule_reliably_saved"]:
            raise RuntimeError("explicit_rule_not_saved")
        # Isolate procedural recall so semantic preference text cannot explain the result.
        for key, record in state["records"].items():
            if record["value"]["kind"] != "ProceduralMemory":
                await service.remove(key)
        report["rules"] = rules
        await service.aclose()
        await history.aclose()
        history = None
        session = await open_agent_session(
            config,
            (selected,),
            ids[1],
            create=True,
            refresh_models=False,
            startup_hooks=False,
            connect_mcp=False,
        )
        result = await session.run(
            Message(
                "explain",
                "user",
                "解释Python函数 def add(a, b): return a + b，在调用 add(2, 3) 时的参数。"
                "只给两行，标签只能用‘用途：’或‘来源：’，顺序遵循你保存的长期讲解规则。不要调用工具。",
            )
        )
        snapshot = await session._store.snapshot()
        loaded = [
            entry.data["records"] for entry in snapshot.entries if entry.type == "memory_load"
        ]
        report["checks"]["procedural_only_recall"] = bool(loaded) and any(
            records and all(record["kind"] == "ProceduralMemory" for record in records)
            for records in loaded
        )
        answer = result.final_text
        report["checks"]["new_session_applies_parameter_order"] = (
            result.outcome == "completed"
            and "来源" in answer
            and "用途" in answer
            and answer.index("来源") < answer.index("用途")
            and "调用" in answer
        )
        report["answer"] = answer
        await session.collect_memory()
        for namespace in session._memory.scopes:
            pending = await session._memory.jobs.pending(namespace, ids[1], str(workspace))
            for job in pending:
                if job.error_code == "memory_claim_unsupported":
                    await session._memory.process(job)
        after = await session._memory.store.snapshot(session._memory.user_namespace)
        report["checks"]["single_task_does_not_rewrite_rule"] = {
            key: row
            for key, row in after["records"].items()
            if row["value"]["kind"] == "ProceduralMemory"
        } == rules
        report["remaining_jobs"] = [
            {"namespace": list(namespace), "job_id": job.job_id, "error_code": job.error_code}
            for namespace in session._memory.scopes
            for job in await session._memory.jobs.pending(namespace, ids[1], str(workspace))
        ]
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
        if service is not None:
            await service.aclose()
        if history is not None:
            await history.aclose()
        ledger = SQLiteRequestStore(StorageLayout(workspace, config.user_data).runtime_database)
        try:
            report["requests"] = [
                asdict(record)
                for session_id in ids
                for record in await ledger.list_requests(session_id)
            ]
        finally:
            await ledger.aclose()
        path = directory / (
            f"recovery-{timestamp}.json" if source_report is not None else "result.json"
        )
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
