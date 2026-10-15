"""Real tool-free inferred-rule comparison; a declined candidate is a valid guarded outcome."""

import asyncio
import hashlib
import json
from dataclasses import asdict
from datetime import datetime, timezone
from pathlib import Path

from archive.scripts.record_verification import manifest

from pycode.app.config import AppConfig, load_providers
from pycode.app.container import (
    build_background_model,
    build_memory_service,
    build_memory_updater,
    build_session_repository,
)
from pycode.core.memory_evidence import (
    MemoryBehaviorFeedback,
    MemoryEvidenceDecision,
    MemoryEvidencePolicy,
    MemoryWritePermit,
)
from pycode.core.memory_jobs import memory_job
from pycode.core.messages import Message
from pycode.core.values import plain_data
from pycode.services.memory_commit import MemoryCommitService, observed_targets
from pycode.services.memory_evidence import capture_memory_evidence
from pycode.workflows.memory_evidence import MemoryEvidenceReviewer
from pycode.workflows.memory_rule_evaluation import MemoryRuleEvaluator
from pycode.workflows.memory_rules import MemoryRuleOptimizer

ROOT = Path(__file__).resolve().parents[2]


async def main():
    run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%f") + "-inferred-rule"
    directory = ROOT / "tests/.runtime-tests/live" / run_id
    workspace = directory / "workspace"
    workspace.mkdir(parents=True)
    configured = load_providers(ROOT / "tests/evals/providers-final.json")[0]
    config = AppConfig(
        workspace, directory / "data", credential_workspace=ROOT, allow_delegation=False
    )
    report = {
        "run_id": run_id,
        "model": configured.model_id,
        "thinking": configured.thinking,
        "source_manifest": manifest(ROOT),
        "checks": {},
        "acceptance": "real_inferred_rule_comparison_gate",
        "limitation": (
            "Fixed synthetic task comparison with model scoring; "
            "not universal coding-quality proof."
        ),
    }
    owner, history, service = None, None, None
    try:
        history = await build_session_repository(config).open(run_id, create=True)
        quote = "刚才那个简短的调用例子让我清楚理解了参数从调用方传入的方式。"
        entry = await history.append_message(Message("rating", "user", quote))
        service = build_memory_service(config, history, configured)
        await service.ensure_store()
        namespace = service.user_namespace
        snapshot = await history.snapshot()
        evidence = await capture_memory_evidence(snapshot)
        policy = MemoryEvidencePolicy(evidence, service.scopes)
        permit = MemoryWritePermit()
        view = await service.store.open_update(validate_change=permit)
        job = await service.jobs.create(
            memory_job(
                namespace,
                snapshot,
                [entry],
                {entry: (0, len(quote))},
                "inferred",
                service.timing.now(),
            )
        )
        job = await service.jobs.update(job, now=service.timing.now(), status="running")
        owner = build_background_model(config, configured, (), run_id, job.job_id, "memory_extract")
        model = build_memory_updater(config, configured).facade(owner, "inferred-rule")
        feedback = MemoryBehaviorFeedback(
            "讲解代码中的函数参数时，使用简短调用示例说明参数从调用方如何传入。",
            "参数讲解的候选方法",
            "短调用示例解释参数来源",
            ("代码讲解", "函数参数解释"),
            ({"session_id": run_id, "entry_id": entry, "quote": quote},),
            MemoryEvidenceDecision(True, True, True),
            explicit=False,
        )
        keys = await MemoryRuleOptimizer(
            model.model_copy(update={"logical_prefix": "memory_rule"}), policy
        ).optimize(view, namespace, feedback, batch_id=job.job_id)

        async def checkpoint(prepared, evaluations):
            nonlocal job
            changes = plain_data(prepared.changes[namespace])
            job = await service.jobs.update(
                job,
                now=service.timing.now(),
                status="running",
                proposals=changes,
                observed_revision=prepared.revisions[namespace],
                observed_targets=observed_targets(
                    changes, plain_data(prepared.previous[namespace])
                ),
                evaluations=evaluations,
            )

        evaluator = MemoryRuleEvaluator(
            model,
            policy,
            run_id=job.job_id,
            checkpoint=checkpoint,
            requests_lookup=lambda: owner.records.list_requests(run_id),
        )
        receipt = evaluator.start(view, namespace, keys, job.job_id, {}, feedback)
        adopted = await evaluator.run(view, receipt)
        report["evaluation"] = receipt.model_dump(mode="json")
        report["adopted"] = adopted
        report["checks"]["complete_fixed_comparison"] = receipt.complete and len(receipt.cases) == 6
        report["checks"]["durable_case_steps"] = (
            await service.jobs.get(namespace, job.job_id)
        ).evaluations[receipt.evaluation_id] == receipt
        reviewer = MemoryEvidenceReviewer(
            model.model_copy(update={"logical_prefix": "memory_evidence"}),
            current_user_text=quote,
            evaluation_context=evaluator.review_context,
        )
        result = await MemoryCommitService(history, policy, permit, reviewer).commit(
            view, namespace
        )
        state = await service.store.snapshot(namespace)
        report["checks"]["only_accepted_candidate_can_be_saved"] = (
            bool(state["records"]) == adopted and result.can_advance
        )
        report["checks"]["request_receipts_verified"] = len(receipt.cases) == 6 and all(
            case.before.request_id and case.after.request_id and case.judgment.request_id
            for case in receipt.cases
        )
        report["status"] = "passed" if all(report["checks"].values()) else "failed"
    except Exception as error:
        report.update(
            status="failed",
            error_type=type(error).__name__,
            error_code=getattr(error, "code", None),
        )
    finally:
        if owner is not None:
            report["requests"] = [
                asdict(record) for record in await owner.records.list_requests(run_id)
            ]
            await owner.aclose()
        if service is not None:
            await service.aclose()
        if history is not None:
            await history.aclose()
        path = directory / "result.json"
        path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
        print(
            json.dumps(
                {
                    "status": report["status"],
                    "checks": report["checks"],
                    "adopted": report.get("adopted"),
                    "error_type": report.get("error_type"),
                    "error_code": report.get("error_code"),
                    "request_count": len(report.get("requests", [])),
                    "report": str(path),
                    "report_sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                    "reported_total_tokens": sum(
                        (record.get("usage") or {}).get("total_tokens") or 0
                        for record in report.get("requests", [])
                    ),
                },
                ensure_ascii=False,
            )
        )
    return 0 if report["status"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
