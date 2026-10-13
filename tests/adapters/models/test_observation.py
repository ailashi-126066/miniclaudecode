from langchain_core.messages import AIMessage

from pycode.adapters.models.observation import ProviderObservation
from pycode.core.models import ModelSelection
from pycode.workflows.messages import normalize_usage


def test_reported_zero_differs_from_missing_field_even_when_sdk_fills_defaults():
    observation = ProviderObservation("openai")
    observation.capture({"id": "provider-1", "usage": {"prompt_tokens": 0}})
    response = AIMessage(
        content="",
        usage_metadata={"input_tokens": 0, "output_tokens": 0, "total_tokens": 0},
        response_metadata=observation.snapshot(),
    )
    usage = normalize_usage(response, ModelSelection("provider", "model", "openai"), "s1", "r1")
    assert usage.input_tokens == 0
    assert usage.output_tokens is None
    assert usage.total_tokens is None
    assert usage.raw["usage_metadata"]["output_tokens"] == 0


def test_anthropic_start_usage_is_kept_in_diagnostics_until_final_settlement():
    observation = ProviderObservation("anthropic")
    observation.capture(
        {
            "type": "message_start",
            "message": {"id": "provider-1", "usage": {"input_tokens": 20, "output_tokens": 0}},
        }
    )
    response = AIMessage(content="", response_metadata=observation.snapshot())
    usage = normalize_usage(response, ModelSelection("provider", "model", "anthropic"), "s1", "r1")
    assert usage.input_tokens is None
    assert usage.raw["response_metadata"]["pycode_provider_usage"]["input_tokens"] == 20
    observation.capture(
        {
            "type": "message_delta",
            "usage": {"output_tokens": 5},
            "delta": {"stop_reason": "end_turn"},
        }
    )
    usage = normalize_usage(
        AIMessage(content="", response_metadata=observation.snapshot()),
        ModelSelection("provider", "model", "anthropic"),
        "s1",
        "r1",
    )
    assert usage.input_tokens == 20
    assert usage.output_tokens == 5


def test_deepseek_reasoning_restoration_does_not_mutate_original_request():
    observation = ProviderObservation("deepseek")
    observation.prepare_reasoning(("thinking", None))
    options = {
        "messages": [
            {"role": "system", "content": "prompt"},
            {"role": "assistant", "content": "one"},
            {"role": "assistant", "content": "two"},
        ]
    }
    restored = observation.request_options(options)
    assert restored["messages"][1]["reasoning_content"] == "thinking"
    assert "reasoning_content" not in restored["messages"][2]
    assert "reasoning_content" not in options["messages"][1]
    observation.begin()
    assert observation.request_options(options) == options
