# 来源：公众号@小林coding
# 后端八股网站：xiaolincoding.com
# Agent网站：xiaolinnote.com
# 简历模版：jianli.xiaolinnote.com

"""工具结果预算在 Agent 主循环里的接线测试：驱动完整主循环，
验证单条溢写、发送副本聚合预算、回读豁免和原历史保持不变。"""
from __future__ import annotations

import copy
from pathlib import Path

import pytest
from pydantic import BaseModel

from mewcode.agent import Agent
from mewcode.conversation import ConversationManager
from mewcode.tools import ToolRegistry
from mewcode.tools.base import (
    StreamEnd,
    TextDelta,
    Tool,
    ToolCallComplete,
    ToolResult,
)

from test_agent import MockLLMClient


class CapturingClient(MockLLMClient):
    def __init__(self, responses):
        super().__init__(responses)
        self.requests = []

    async def stream(self, conversation, system="", tools=None):
        self.requests.append(copy.deepcopy(conversation))
        async for event in super().stream(conversation, system, tools):
            yield event


class _EmptyParams(BaseModel):
    pass


class _ReadbackParams(BaseModel):
    file_path: str = ""


class FixedOutputTool(Tool):
    """返回固定内容的假工具。"""

    description = "fixed output"
    category = "read"
    params_model = _EmptyParams

    def __init__(self, name: str, output: str) -> None:
        self.name = name
        self._output = output

    async def execute(self, params: BaseModel) -> ToolResult:
        return ToolResult(output=self._output)


class FakeReadFileTool(Tool):
    """名字叫 ReadFile 的假工具，用于触发回读豁免判定。"""

    name = "ReadFile"
    description = "fake readfile"
    category = "read"
    params_model = _ReadbackParams

    def __init__(self, output: str) -> None:
        self._output = output

    async def execute(self, params: BaseModel) -> ToolResult:
        return ToolResult(output=self._output)


def _tool_results_msg(conv: ConversationManager):
    for msg in conv.history:
        if msg.tool_results:
            return msg
    raise AssertionError("no tool-results message in conversation")


def _end_turn() -> list:
    return [TextDelta("done"), StreamEnd("end_turn", input_tokens=1, output_tokens=1)]


@pytest.mark.asyncio
async def test_ingest_spills_single_oversized_result(tmp_path: Path, monkeypatch) -> None:
    monkeypatch.chdir(tmp_path)
    client = CapturingClient([
        [ToolCallComplete("t1", "BigTool", {}), StreamEnd("tool_use", 1, 1)],
        _end_turn(),
    ])
    registry = ToolRegistry()
    registry.register(FixedOutputTool("BigTool", "x" * 60000))
    agent = Agent(client, registry, "anthropic", work_dir=str(tmp_path))
    conv = ConversationManager()
    conv.add_user_message("go")

    async for _ in agent.run(conv):
        pass

    # 进历史的内容是预览，不是原文
    tr = _tool_results_msg(conv).tool_results[0]
    assert tr.content.startswith("<persisted-output>")
    # 溢写文件保存了完整原文
    spill = agent.session_dir / "t1.txt"
    assert spill.exists()
    assert len(spill.read_text(encoding="utf-8")) == 60000


@pytest.mark.asyncio
async def test_ingest_readback_exempt(tmp_path: Path, monkeypatch) -> None:
    monkeypatch.chdir(tmp_path)
    registry = ToolRegistry()
    registry.register(FakeReadFileTool("y" * 60000))
    # 先构造 agent 拿到 session_dir，再让 ReadFile 指向该目录下的文件
    client_placeholder = MockLLMClient([])
    probe = Agent(client_placeholder, registry, "anthropic", work_dir=str(tmp_path))
    readback_path = str(probe.session_dir / "toolu_old.txt")

    client = CapturingClient([
        [
            ToolCallComplete("t_rb", "ReadFile", {"file_path": readback_path}),
            StreamEnd("tool_use", 1, 1),
        ],
        _end_turn(),
    ])
    agent = Agent(client, registry, "anthropic", work_dir=str(tmp_path))
    conv = ConversationManager()
    conv.add_user_message("read it back")

    async for _ in agent.run(conv):
        pass

    # 回读结果豁免溢写：原文进历史，且没有生成新的溢写文件
    tr = _tool_results_msg(conv).tool_results[0]
    assert len(tr.content) == 60000
    assert not (agent.session_dir / "t_rb.txt").exists()
    assert len(_tool_results_msg(client.requests[1]).tool_results[0].content) == 60000


@pytest.mark.asyncio
@pytest.mark.parametrize("interactive", [True, False])
async def test_request_aggregate_spills_largest_without_changing_history(tmp_path: Path, monkeypatch, interactive) -> None:
    monkeypatch.chdir(tmp_path)
    sizes = {"T1": 45000, "T2": 45000, "T3": 45001, "T4": 45000, "T5": 45000}
    registry = ToolRegistry()
    calls = []
    for name, n in sizes.items():
        registry.register(FixedOutputTool(name, "z" * n))
        calls.append(ToolCallComplete("t" + name[1:].lower(), name, {}))
    client = CapturingClient([
        [*calls, StreamEnd("tool_use", 1, 1)],
        _end_turn(),
    ])
    agent = Agent(client, registry, "anthropic", work_dir=str(tmp_path))
    conv = ConversationManager()
    conv.add_user_message("fan out")

    if interactive:
        async for _ in agent.run(conv):
            pass
    else:
        await agent.run_to_completion("", conversation=conv)

    original = _tool_results_msg(conv)
    assert sum(len(tr.content) for tr in original.tool_results) == 225001
    assert all(not tr.content.startswith("<persisted-output>") for tr in original.tool_results)
    msg = _tool_results_msg(client.requests[1])
    total = sum(len(tr.content) for tr in msg.tool_results)
    assert total <= 200000
    previews = [tr for tr in msg.tool_results if tr.content.startswith("<persisted-output>")]
    assert len(previews) == 1
    t3 = next(tr for tr in msg.tool_results if tr.tool_use_id == "t3")
    assert t3.content.startswith("<persisted-output>")
