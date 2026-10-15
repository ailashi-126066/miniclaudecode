"""Single metered diagnostic records only a redacted public SDK error message."""

import asyncio
import json
from datetime import datetime, timezone
from pathlib import Path

import openai
from langchain.agents.middleware import ModelResponse
from langchain_core.messages import HumanMessage

from pycode.app.config import AppConfig, credential_environment, load_providers
from pycode.app.container import build_background_model
from pycode.core.requests import RetryPolicy
from pycode.workflows.memory_evidence import MemoryEvidenceVerdict
from pycode.workflows.model_requests import ModelMessages

ROOT = Path(__file__).resolve().parents[2]


async def main():
    run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%f") + "-structured-diagnostic"
    directory = ROOT / "tests/.runtime-tests/live" / run_id
    directory.mkdir(parents=True)
    provider = load_providers(ROOT / "evals/data/providers-final.json")[0]
    config = AppConfig(ROOT, directory / "data", credential_workspace=ROOT)
    key = credential_environment(config, (provider,)).get(provider.api_key_env, "")
    report = {"model": provider.model_id, "thinking": provider.thinking, "tool_choice": "any"}
    owner = build_background_model(
        config,
        provider,
        (),
        run_id,
        run_id,
        "memory_extract",
        retry_policy=RetryPolicy(max_retries=0),
    )
    bound = owner.model.bind_tools([MemoryEvidenceVerdict], tool_choice="any")

    async def invoke(request):
        try:
            message = await bound.ainvoke(request.messages, stream=True)
            return ModelResponse(result=[message])
        except openai.APIStatusError as error:
            body = error.body if isinstance(error.body, dict) else {}
            data = body.get("error", body)
            message = data.get("message", "") if isinstance(data, dict) else ""
            report["http_status"] = error.status_code
            report["redacted_message"] = (
                str(message).replace(key, "[redacted]")[:800] if key else ""
            )
            raise

    try:
        await owner.requests.invoke(
            ModelMessages(
                [
                    HumanMessage(
                        content=(
                            "Return a MemoryEvidenceVerdict: supported=true, durable=true, "
                            "scope_valid=true, reason=explicit preference."
                        )
                    )
                ]
            ),
            invoke,
            logical_id="diagnostic",
        )
        report["status"] = "passed"
    except Exception as error:
        report["status"] = "failed"
        report["error_type"] = type(error).__name__
        report["error_code"] = getattr(error, "code", None)
    finally:
        await owner.aclose()
    path = directory / "result.json"
    path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({**report, "report": str(path)}, ensure_ascii=False))


if __name__ == "__main__":
    asyncio.run(main())
