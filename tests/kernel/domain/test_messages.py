from pycode.kernel.contracts.messages import (
    Message,
    ThinkingBlock,
    ToolCall,
    ToolResult,
    UsageRecord,
)


def test_complete_model_contract_retains_thinking_tools_and_unknown_usage():
    usage = UsageRecord(provider="deepseek", model="configured-model", request_id="req-1")
    message = Message(
        id="assistant-1",
        role="assistant",
        text="Checking",
        stop_reason="tool_calls",
        thinking=(ThinkingBlock(text="Reasoning", signature="provider-signature"),),
        tool_calls=(ToolCall(id="call-1", name="Read", arguments={"path": "example.py"}),),
        usage=usage,
        provider_metadata={"raw_finish_reason": "tool_calls"},
    )
    assert message.thinking[0].signature == "provider-signature"
    assert message.tool_calls[0].id == "call-1"
    assert message.usage.input_tokens is None
    assert message.usage.cache_read_tokens is None
    assert message.provider_metadata["raw_finish_reason"] == "tool_calls"


def test_error_result_preserves_call_pairing_and_output_reference():
    result = ToolResult(
        call_id="call-1",
        name="Bash",
        text="failed",
        is_error=True,
        error_code="command_failed",
        exit_code=1,
        output_id="output-1",
    )
    message = Message(id="result-1", role="tool", tool_result=result)
    assert message.tool_result.call_id == "call-1"
    assert message.tool_result.exit_code == 1
    assert message.tool_result.output_id == "output-1"
