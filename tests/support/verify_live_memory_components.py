"""Opt-in real provider probe; credentials stay outside the report and project data."""

import asyncio
import hashlib
import json
from dataclasses import asdict, replace
from datetime import datetime, timezone
from pathlib import Path

from langmem import create_prompt_optimizer
from tools.record_verification import manifest
from typing_extensions import TypedDict

from pycode.adapters.storage.json_memory import JsonMemoryStore
from pycode.adapters.storage.session import JsonlSessionStore
from pycode.adapters.timing import SystemTiming
from pycode.app.config import AppConfig, credential_environment, load_providers
from pycode.app.container import build_background_model, build_memory_updater
from pycode.core.memory_evidence import MemoryWritePermit
from pycode.core.messages import Message
from pycode.core.requests import RetryPolicy
from pycode.core.session import SessionHeader
from pycode.services.memory_evidence import capture_memory_evidence
from pycode.workflows.memory_query import MemoryDirectorySelector
from pycode.workflows.metered_model import MeteredChatModel

SOURCE_FILE = Path(__file__).resolve()
ROOT = SOURCE_FILE.parents[2]
CREDENTIAL_ROOT = ROOT


class RuleOutput(TypedDict):
    rule: str


async def main():
    run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%f") + "-live-memory"
    directory = ROOT / "tests" / ".runtime-tests" / "live" / run_id
    directory.mkdir(parents=True)
    provider_path = ROOT / "tests" / "evals" / "providers-final.json"
    configured = replace(
        load_providers(provider_path)[0],
        thinking={"type": "disabled"},
        max_output_tokens=1024,
        timeout_seconds=30,
    )
    config = AppConfig(
        ROOT,
        directory / "data",
        credential_workspace=CREDENTIAL_ROOT,
    )
    report = {
        "run_id": run_id,
        "acceptance": "real_remote_service",
        "model": configured.model_id,
        "thinking": "disabled",
        "max_output_tokens": configured.max_output_tokens,
        "credential_present": bool(
            credential_environment(config, (configured,)).get(configured.api_key_env)
        ),
        "source_manifest": manifest(ROOT),
        "source_sha256": {
            str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest()
            for path in (
                SOURCE_FILE,
                ROOT / "src/pycode/workflows/metered_model.py",
                ROOT / "src/pycode/adapters/models/observation.py",
            )
        },
        "checks": {},
        "limitation": (
            "A live protocol/component smoke check; not a quality comparison or full evaluation."
        ),
    }
    owner = None
    history = None
    try:
        if not report["credential_present"]:
            raise RuntimeError("credential_missing")
        owner = build_background_model(
            config,
            configured,
            (),
            run_id,
            run_id,
            "memory_extract",
            retry_policy=RetryPolicy(max_retries=0),
        )
        model = MeteredChatModel(
            model=owner.model,
            requests=owner.requests,
            logical_prefix="live-memory",
            input_limit=12000,
        )
        result = await model.with_structured_output(RuleOutput, method="json_schema").ainvoke(
            "生成一条中文代码讲解规则：先说明参数从哪里传入，再说明其用途。"
        )
        report["checks"]["native_structured_output"] = (
            isinstance(result, dict)
            and isinstance(result.get("rule"), str)
            and bool(result["rule"].strip())
        )
        updated = await create_prompt_optimizer(model, kind="prompt_memory").ainvoke(
            {
                "trajectories": (
                    "用户明确纠正：讲解函数前，先说明各参数从调用方哪里传入。"
                    "以后讲解代码都遵循这条规则。"
                ),
                "prompt": {
                    "name": "live-synthetic-rule",
                    "prompt": "解释代码中的函数及参数用途。",
                    "update_instructions": "只依据用户明确纠正更新这条讲解规则，保留参数用途要求。",
                    "when_to_update": "用户明确纠正代码讲解顺序时。",
                },
            }
        )
        report["checks"]["official_single_prompt_optimizer"] = isinstance(updated, str) and bool(
            updated.strip()
        )
        namespace = ("memories", "user", "live")
        history = JsonlSessionStore(
            directory / "sessions" / run_id,
            SessionHeader(run_id, str(ROOT), "live"),
            SystemTiming(),
        )
        entry_id = await history.append_message(
            Message(
                "live-user",
                "user",
                "以后讲解代码时，先说明各参数从调用方哪里传入，再说明参数用途。",
            )
        )
        store = JsonMemoryStore(
            {namespace: directory / "memory"},
            selector=MemoryDirectorySelector(model),
            validate_change=MemoryWritePermit(),
        )
        snapshot = await history.snapshot()
        evidence = await capture_memory_evidence(snapshot)
        result = await build_memory_updater(config, configured).update(
            history,
            store,
            {namespace: "user"},
            namespace,
            snapshot,
            run_id + "-update",
            entry_ids=[entry_id],
            batch_id="live-batch",
            progress={
                "session_id": run_id,
                "branch_id": snapshot.leaf_id,
                "entry_ids": [entry_id],
                "prefix_hash": evidence.prefix_hash,
                "start": {"entry_id": entry_id, "offset": 0},
                "end": {"entry_id": entry_id, "offset": len(evidence.texts[0].text)},
            },
        )
        report["checks"]["official_manager_validated_json_commit"] = result.can_advance and bool(
            result.accepted_ids
        )
        records = await store.asearch(namespace)
        report["checks"]["source_and_batch_receipt"] = (
            bool(records)
            and all(
                source["entry_id"] == entry_id and source["session_id"] == run_id
                for record in records
                for source in record.value["content"]["sources"]
            )
            and bool(store.read(namespace)["processed_batches"])
        )
        recalled = await build_memory_updater(config, configured).recall(
            store,
            {namespace: "user"},
            run_id,
            run_id + "-recall",
            "解释函数参数传递时应遵循什么讲解顺序？",
        )
        report["checks"]["subsequent_directory_recall"] = bool(recalled)
        report["status"] = "passed" if all(report["checks"].values()) else "failed"
    except Exception as error:
        report["status"] = "failed"
        report["error_type"] = type(error).__name__
        report["error_code"] = getattr(error, "code", None)
    finally:
        if owner is not None:
            report["requests"] = [
                asdict(record) for record in await owner.records.list_requests(run_id)
            ]
            await owner.aclose()
        if history is not None:
            await history.aclose()
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
                    "report": str(path),
                    "request_count": len(report.get("requests", [])),
                    "reported_total_tokens": sum(
                        (item.get("usage") or {}).get("total_tokens") or 0
                        for item in report.get("requests", [])
                    ),
                },
                ensure_ascii=False,
            )
        )
    return 0 if report["status"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
