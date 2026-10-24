"""Pin the SDK runnable shapes MeteredChatModel.with_structured_output relies on.

The metered component replaces the first step of the SDK-built sequence so
internal structured-output calls stay metered. If an SDK upgrade changes these
shapes, these tests fail loudly instead of the runtime raising
component_protocol_unsupported against a real provider.

Default and json_schema methods bind ``response_format`` without tools; the
surgery tolerates empty tools and only requires the binding itself.
"""

from langchain_core.runnables import RunnableBinding, RunnableParallel, RunnableSequence
from langchain_openai import ChatOpenAI
from pydantic import BaseModel, SecretStr


class ContractAnswer(BaseModel):
    text: str


def build_model():
    return ChatOpenAI(model="contract", api_key=SecretStr("test-key"), max_retries=0)


def test_sdk_structured_output_is_sequence_with_binding_first():
    native = build_model().with_structured_output(ContractAnswer)
    assert isinstance(native, RunnableSequence)
    assert isinstance(native.steps[0], RunnableBinding)


def test_sdk_function_calling_binding_carries_tools():
    native = build_model().with_structured_output(ContractAnswer, method="function_calling")
    assert isinstance(native, RunnableSequence)
    first = native.steps[0]
    assert isinstance(first, RunnableBinding)
    assert first.kwargs.get("tools"), "SDK binding no longer carries tool schemas"


def test_sdk_structured_output_raw_wraps_binding_in_parallel():
    native = build_model().with_structured_output(ContractAnswer, include_raw=True)
    first = native.steps[0]
    assert isinstance(first, RunnableParallel)
    assert set(first.steps__) == {"raw"}
    assert isinstance(first.steps__["raw"], RunnableBinding)
