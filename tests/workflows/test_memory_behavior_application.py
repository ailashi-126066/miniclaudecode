"""Production background stages explicit rules, skips temporary feedback and recalls them."""

import json
import re

import pytest
from tests.support.execution import provider
from tests.support.http_provider import chat_completion, protocol_server
from tests.workflows.test_memory_rules import json_reply
from tests.workflows.test_skills_http import skill_reply

from pycode.app.config import AppConfig
from pycode.app.container import build_memory_service, build_session_repository, open_agent_session
from pycode.core.messages import Message

BODY = "Always explain where parameters come from using short examples."
RULE = "Explain parameter inputs with a short example."


def semantic(entry, text=BODY):
    return {
        "title": "Explanation preference",
        "summary": "Explain parameter sources",
        "body": text,
        "category": "user_preference",
        "sources": [{"session_id": "s", "entry_id": entry, "quote": text}],
    }


async def seed_rules(service, count):
    for index in range(count):
        body = f"Explain code using original rule {index}."
        value = {
            "kind": "ProceduralMemory",
            "content": {
                "title": "Code explanation",
                "summary": "Explain code",
                "body": body,
                "applicability": ["Code explanation"],
                "sources": [],
                "origin": "user_explicit",
            },
        }
        await service.command(
            service.user_namespace,
            {f"rule-{index}": value},
            {f"rule-{index}": None},
            "Remember this lasting rule:\n" + body,
        )


def responder(holder, *, classify=True, explicit=True, bad_key=False, invalid_rule=False):
    def respond(request):
        names = {tool["function"]["name"] for tool in request["body"].get("tools", [])}
        text = json.dumps(request["body"]["messages"])
        if "MemoryDirectoryChoices" in names:
            raw = request["body"]["messages"][-1]["content"]
            directory = json.loads(raw.split("Memory directory:\n", 1)[1])
            return skill_reply(
                "MemoryDirectoryChoices",
                {"selections": [{"id": row["id"], "score": 1.0} for row in directory]},
                "directory",
            )
        if "MemoryFeedbackChoices" in names:
            raw = request["body"]["messages"][-1]["content"]
            candidates = json.loads(raw.split("trusted original user quotes:\n", 1)[1])
            keys = ["foreign"] if bad_key else list(candidates)
            choices = (
                [
                    {
                        "keys": keys,
                        "text": holder.get("body", BODY),
                        "title": "Explanation preference",
                        "summary": "Explain parameter sources",
                        "applicability": ["Code explanation"],
                        "explicit": explicit,
                    }
                ]
                if classify
                else []
            )
            return skill_reply("MemoryFeedbackChoices", {"feedback": choices}, "feedback")
        if "MemoryEvidenceVerdict" in names:
            return skill_reply(
                "MemoryEvidenceVerdict",
                {
                    "supported": True,
                    "durable": True,
                    "scope_valid": True,
                    "reason": "lasting instruction",
                },
                "review",
            )
        if "SemanticMemory" in names:
            if (
                "for the project scope" in text
                or holder.get("main_saw_rule")
                or holder.get("body", "").startswith("Only this time")
            ):
                return chat_completion()
            return skill_reply(
                "SemanticMemory", semantic(holder["entry"], holder.get("body", BODY)), "fact"
            )
        if "Classify" in names:
            ids = sorted(set(re.findall(r"rule-[a-f0-9]{64}", text)))
            return skill_reply("Classify", {"reasoning": "both relevant", "which": ids}, "classify")
        if "RuleComparisonScore" in names:
            return skill_reply("RuleComparisonScore", {}, "invalid-comparison")
        if "synthetic evaluation task" in request["body"]["messages"][0]["content"]:
            return chat_completion()
        if not names or "GeneralResponse" in names:
            holder["optimizers"] = holder.get("optimizers", 0) + 1
            return json_reply("openai", {"invalid": "object"} if invalid_rule else RULE)
        # The following task uses the same production Store, with a fresh conversation.
        assert RULE in text and "ProceduralMemory" in text
        holder["main_saw_rule"] = True
        response = chat_completion()
        response.body = response.body.replace(
            b'"done"', b'"Arguments come from the caller; use a short example."'
        )
        return response

    return respond


@pytest.mark.parametrize("count", [0, 1, 2])
async def test_production_single_multi_rule_branch_persists_and_applies_in_next_session(
    tmp_path, monkeypatch, count
):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    holder = {}
    with protocol_server(responder(holder)) as (url, captured):
        config = AppConfig(tmp_path, tmp_path / "data", allow_delegation=False)
        history = await build_session_repository(config).open("s", create=True)
        service = build_memory_service(config, history, provider(url))
        next_session = None
        try:
            await service.initialize()
            await seed_rules(service, count)
            holder["entry"] = await history.append_message(Message("u", "user", BODY))
            await service.settled("run")
            await service.collect()
            state = await service.store.snapshot(service.user_namespace)
            rules = {
                key: value
                for key, value in state["records"].items()
                if value["value"]["kind"] == "ProceduralMemory"
            }
            assert len(rules) == max(1, count)
            assert all(value["value"]["content"]["body"] == RULE for value in rules.values())
            assert all(
                value["value"]["content"]["origin"] == "user_explicit" for value in rules.values()
            )
            if count:
                assert set(rules) == {f"rule-{i}" for i in range(count)}
            assert holder["optimizers"] == max(1, count)
            assert sum(
                "Classify" in {t["function"]["name"] for t in r["body"].get("tools", [])}
                for r in captured
            ) == (1 if count == 2 else 0)
            assert state["processed_batches"]
            next_session = await open_agent_session(
                config,
                (provider(url),),
                "next",
                create=True,
                refresh_models=False,
                startup_hooks=False,
                connect_mcp=False,
            )
            result = await next_session.run(
                Message("next-user", "user", "Explain a Python function's parameters.")
            )
            assert result.outcome == "completed" and holder["main_saw_rule"]
            await next_session.collect_memory()
        finally:
            if next_session is not None:
                await next_session.aclose()
            await service.aclose()
            await history.aclose()


@pytest.mark.parametrize(
    "case", ["temporary", "ordinary_preference", "unknown_key", "inferred", "invalid_rule"]
)
async def test_behavior_skip_and_failures_never_publish_unverified_rules(
    tmp_path, monkeypatch, case
):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    holder = {
        "body": (
            "Only this time, explain briefly."
            if case == "temporary"
            else "I prefer black coffee."
            if case == "ordinary_preference"
            else BODY
        )
    }
    respond = responder(
        holder,
        classify=case not in {"temporary", "ordinary_preference"},
        explicit=case != "inferred",
        bad_key=case == "unknown_key",
        invalid_rule=case == "invalid_rule",
    )
    with protocol_server(respond) as (url, captured):
        config = AppConfig(tmp_path, tmp_path / "data", allow_delegation=False)
        history = await build_session_repository(config).open("s", create=True)
        holder["entry"] = await history.append_message(Message("u", "user", holder["body"]))
        service = build_memory_service(config, history, provider(url))
        try:
            await service.initialize()
            await service.settled("run")
            await service.collect()
            state = await service.store.snapshot(service.user_namespace)
            assert not any(
                value["value"]["kind"] == "ProceduralMemory" for value in state["records"].values()
            )
            if case in {"temporary", "ordinary_preference"}:
                assert not holder.get("optimizers") and state["processed_batches"]
            else:
                assert not state["records"] and not state["processed_batches"]
                pending = await service.jobs.pending(service.user_namespace, "s", str(tmp_path))
                assert len(pending) == 1 and pending[0].status == "pending_retry"
                assert pending[0].reorganize_reason == (
                    None if case == "inferred" else "memory_behavior_stage_failed"
                )
                if case in {"invalid_rule", "inferred"}:
                    assert holder["optimizers"] == 1
                else:
                    assert not holder.get("optimizers")
        finally:
            await service.aclose()
            await history.aclose()


async def test_rule_stage_failure_keeps_related_feedback_pending_but_preserves_independent_case(
    tmp_path, monkeypatch
):
    from pycode.app.container import build_memory_service

    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    episode_quote = "We once printed function arguments while debugging."
    holder = {"body": BODY, "repair": False}
    ordinary = responder(holder)

    def respond(request):
        names = {tool["function"]["name"] for tool in request["body"].get("tools", [])}
        text = json.dumps(request["body"]["messages"])
        if "MemoryFeedbackChoices" in names and not holder["repair"]:
            return skill_reply(
                "MemoryFeedbackChoices", {"feedback": [{"keys": ["invented"]}]}, "bad-feedback"
            )
        if (
            "SemanticMemory" in names
            and not holder["repair"]
            and "for the project scope" not in text
        ):
            response = skill_reply("SemanticMemory", semantic(holder["entry"]), "preference")
            episode = {
                "title": "Debugging case",
                "summary": "Printed arguments",
                "body": episode_quote,
                "situation": "Debugging a function",
                "actions": ["Printed arguments"],
                "outcome": "Observed argument values",
                "applicability": ["Argument debugging"],
                "verification": "unverified",
                "sources": [
                    {"session_id": "s", "entry_id": holder["entry"], "quote": episode_quote}
                ],
            }
            lines = []
            for line in response.body.decode().splitlines():
                if line.startswith("data: {"):
                    chunk = json.loads(line[6:])
                    for choice in chunk.get("choices", []):
                        calls = choice.get("delta", {}).get("tool_calls")
                        if calls:
                            calls.append(
                                {
                                    "index": 1,
                                    "id": "episode",
                                    "type": "function",
                                    "function": {
                                        "name": "EpisodicMemory",
                                        "arguments": json.dumps(episode),
                                    },
                                }
                            )
                    line = "data: " + json.dumps(chunk)
                lines.append(line)
            response.body = ("\n".join(lines) + "\n").encode()
            return response
        return ordinary(request)

    with protocol_server(respond) as (url, captured):
        config = AppConfig(tmp_path, tmp_path / "data", allow_delegation=False)
        history = await build_session_repository(config).open("s", create=True)
        holder["entry"] = await history.append_message(
            Message("u", "user", BODY + " " + episode_quote)
        )
        service = build_memory_service(config, history, provider(url))
        reopened = None
        try:
            await service.initialize()
            await service.settled("run")
            await service.collect()
            namespace = service.user_namespace
            first = await service.store.snapshot(namespace)
            assert len(first["records"]) == 1 and not first["processed_batches"]
            key, record = next(iter(first["records"].items()))
            assert record["value"]["kind"] == "EpisodicMemory"
            pending = await service.jobs.pending(namespace, "s", str(tmp_path))
            assert (
                len(pending) == 1 and pending[0].reorganize_reason == "memory_behavior_stage_failed"
            )
            await service.aclose()
            holder["repair"] = True
            reopened = build_memory_service(config, history, provider(url))
            assert await reopened.process(await reopened.jobs.get(namespace, pending[0].job_id))
            final = await reopened.store.snapshot(namespace)
            assert final["records"][key] == record
            assert {value["value"]["kind"] for value in final["records"].values()} == {
                "SemanticMemory",
                "EpisodicMemory",
                "ProceduralMemory",
            }
            assert pending[0].job_id in final["processed_batches"]
            assert (await reopened.jobs.get(namespace, pending[0].job_id)).status == "completed"
        finally:
            if reopened is not None:
                await reopened.aclose()
            await service.aclose()
            await history.aclose()


async def test_failed_rule_stage_follows_fact_deduplication_without_advancing_existing_preference(
    tmp_path, monkeypatch
):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    holder = {}
    with protocol_server(responder(holder, bad_key=True)) as (url, captured):
        config = AppConfig(tmp_path, tmp_path / "data", allow_delegation=False)
        history = await build_session_repository(config).open("s", create=True)
        service = build_memory_service(config, history, provider(url))
        try:
            await service.initialize()
            value = {"kind": "SemanticMemory", "content": semantic("unused")}
            value["content"]["sources"] = []
            await service.command(
                service.user_namespace, {"existing": value}, {"existing": None}, BODY
            )
            before = await service.store.snapshot(service.user_namespace)
            holder["entry"] = await history.append_message(Message("u", "user", BODY))
            await service.settled("run")
            await service.collect()
            after = await service.store.snapshot(service.user_namespace)
            assert after["records"] == before["records"] and not after["processed_batches"]
            pending = await service.jobs.pending(service.user_namespace, "s", str(tmp_path))
            assert (
                len(pending) == 1 and pending[0].reorganize_reason == "memory_behavior_stage_failed"
            )
            assert not holder.get("optimizers")
        finally:
            await service.aclose()
            await history.aclose()
