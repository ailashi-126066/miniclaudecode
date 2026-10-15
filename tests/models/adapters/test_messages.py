import pytest
from langchain_core.messages import AIMessage

from pycode.kernel import ModelSelection
from pycode.models.adapters.messages import from_assistant, normalize_usage, to_framework


@pytest.mark.parametrize(
    ("implementation", "metadata", "expected"),
    [
        (
            "deepseek",
            {
                "token_usage": {
                    "prompt_tokens": 20,
                    "completion_tokens": 5,
                    "total_tokens": 25,
                    "prompt_cache_hit_tokens": 12,
                    "prompt_cache_miss_tokens": 8,
                    "completion_tokens_details": {"reasoning_tokens": 3},
                }
            },
            (20, 5, 12, 8, None, 3),
        ),
        (
            "openai",
            {
                "token_usage": {
                    "prompt_tokens": 20,
                    "completion_tokens": 5,
                    "total_tokens": 25,
                    "prompt_tokens_details": {"cached_tokens": 0},
                    "completion_tokens_details": {"reasoning_tokens": 3},
                }
            },
            (20, 5, 0, None, None, 3),
        ),
        (
            "anthropic",
            {
                "usage": {
                    "input_tokens": 20,
                    "output_tokens": 5,
                    "cache_read_input_tokens": 12,
                    "cache_creation_input_tokens": 2,
                }
            },
            (20, 5, 12, None, 2, None),
        ),
    ],
)
def test_provider_specific_usage_is_retained_without_fabricated_zeros(
    implementation, metadata, expected
):
    selection = ModelSelection("configured-provider", "configured-model", implementation)
    response = AIMessage(id="req-1", content="text", response_metadata=metadata)
    usage = normalize_usage(response, selection, "session-1", "run-1")
    values = (
        usage.input_tokens,
        usage.output_tokens,
        usage.cache_read_tokens,
        usage.cache_miss_tokens,
        usage.cache_write_tokens,
        usage.reasoning_tokens,
    )
    assert values == expected
    assert usage.raw["response_metadata"] == metadata


def test_deepseek_reasoning_and_tool_calls_round_trip_at_framework_boundary():
    selection = ModelSelection("local", "model", "deepseek")
    response = AIMessage(
        id="a1",
        content="reply",
        additional_kwargs={"reasoning_content": "think"},
        tool_calls=[{"id": "call-1", "name": "echo", "args": {"value": "hello"}}],
    )
    normalized = from_assistant(response, selection, "s1", "r1")
    restored = to_framework(normalized, selection)
    assert restored.additional_kwargs["reasoning_content"] == "think"
    assert restored.tool_calls[0]["id"] == "call-1"
    assert normalized.usage.cache_read_tokens is None


def test_anthropic_thinking_signature_and_redaction_are_preserved():
    selection = ModelSelection("local", "model", "anthropic")
    response = AIMessage(
        id="a1",
        content=[
            {"type": "thinking", "thinking": "think", "signature": "signed"},
            {"type": "redacted_thinking", "data": "redacted"},
            {"type": "text", "text": "reply"},
        ],
    )
    normalized = from_assistant(response, selection, "s1", "r1")
    restored = to_framework(normalized, selection)
    assert normalized.thinking[0].signature == "signed"
    assert normalized.thinking[1].redacted_data == "redacted"
    assert restored.content == response.content


def test_malformed_tool_arguments_remain_invalid_even_with_an_empty_schema():
    response = AIMessage(
        id="a1",
        content="",
        invalid_tool_calls=[
            {"id": "call-1", "name": "echo", "args": "{broken", "error": "not valid JSON"},
        ],
    )
    normalized = from_assistant(response, ModelSelection("local", "model", "openai"), "s1", "r1")
    assert normalized.tool_calls[0].parse_error == "not valid JSON"
    assert normalized.tool_calls[0].raw_arguments == "{broken"


def test_openai_responses_reasoning_summary_is_not_lost():
    block = {
        "type": "reasoning",
        "id": "rs-1",
        "summary": [
            {"type": "summary_text", "text": "reasoning summary"},
        ],
    }
    response = AIMessage(id="a1", content=[block, {"type": "text", "text": "reply"}])
    normalized = from_assistant(response, ModelSelection("local", "model", "openai"), "s1", "r1")
    assert normalized.thinking[0].text == "reasoning summary"
    assert normalized.thinking[0].provider_data == block
    restored = to_framework(
        normalized, ModelSelection("local", "model", "openai", api_mode="responses")
    )
    assert restored.content[0] == block
