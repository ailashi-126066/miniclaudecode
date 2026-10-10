"""Execution batches preserve write barriers, result order, and interactive approval."""
import asyncio
from types import SimpleNamespace

import pytest
from pydantic import BaseModel

from mewcode.agent import Agent, PermissionRequest, PermissionResponse, ToolResultEvent
from mewcode.conversation import ConversationManager
from mewcode.permissions import Decision, PermissionMode
from mewcode.tools import ToolRegistry, create_default_registry
from mewcode.tools.base import StreamEnd, TextDelta, Tool, ToolCallComplete, ToolResult
from test_agent import MockLLMClient


class Params(BaseModel):
    label: str


class ObservedRead(Tool):
    name = 'Observe'
    description = 'Observe shared test state'
    params_model = Params
    category = 'read'
    is_concurrency_safe = True

    def __init__(self, state):
        self.state = state

    async def execute(self, params):
        state = self.state
        assert not state.writing
        state.active_reads += 1
        state.trace.append(('read-start', params.label))
        if params.label in {'r1', 'r2'}:
            if state.active_reads == 2:
                state.gate.set()
            await asyncio.wait_for(state.gate.wait(), 1)
        await asyncio.sleep(0)
        result = ToolResult(output=str(state.value))
        state.active_reads -= 1
        state.trace.append(('read-end', params.label))
        return result


class ObservedWrite(Tool):
    name = 'Update'
    description = 'Update shared test state'
    params_model = Params
    category = 'write'

    def __init__(self, state):
        self.state = state

    async def execute(self, params):
        state = self.state
        assert state.active_reads == 0
        assert not state.writing
        state.writing = True
        state.trace.append(('write-start', params.label))
        await asyncio.sleep(0)
        state.value += 1
        state.writing = False
        state.trace.append(('write-end', params.label))
        return ToolResult(output='updated')


@pytest.mark.asyncio
async def test_safe_reads_overlap_and_writes_form_barriers(tmp_path):
    state = SimpleNamespace(active_reads=0, writing=False, value=0, gate=asyncio.Event(), trace=[])
    registry = ToolRegistry()
    registry.register(ObservedRead(state))
    registry.register(ObservedWrite(state))
    calls = [ToolCallComplete(label, name, {'label': label}) for label, name in [
        ('r1', 'Observe'), ('r2', 'Observe'), ('w1', 'Update'),
        ('r3', 'Observe'), ('w2', 'Update'), ('r4', 'Observe'),
    ]]
    client = MockLLMClient([
        [*calls, StreamEnd('tool_use', 1, 1)],
        [TextDelta('done'), StreamEnd('end_turn', 1, 1)],
    ])
    agent = Agent(client, registry, 'anthropic', work_dir=str(tmp_path))
    conv = ConversationManager()
    conv.add_user_message('run')
    events = [e async for e in agent.run(conv)]
    results = [e for e in events if isinstance(e, ToolResultEvent)]
    assert all(not e.is_error for e in results)
    assert [e.tool_id for e in results] == ['r1', 'r2', 'w1', 'r3', 'w2', 'r4']
    assert [e.output for e in results if e.tool_name == 'Observe'] == ['0', '0', '1', '2']
    assert state.trace.index(('write-start', 'w1')) > max(
        state.trace.index(('read-end', 'r1')), state.trace.index(('read-end', 'r2'))
    )
    assert state.trace.index(('read-start', 'r3')) > state.trace.index(('write-end', 'w1'))
    assert state.trace.index(('read-start', 'r4')) > state.trace.index(('write-end', 'w2'))


@pytest.mark.asyncio
async def test_read_batch_requiring_approval_uses_interactive_execution(tmp_path):
    state = SimpleNamespace(active_reads=0, writing=False, value=0, gate=asyncio.Event(), trace=[])
    registry = ToolRegistry()
    registry.register(ObservedRead(state))
    checker = SimpleNamespace(mode=PermissionMode.DEFAULT, check=lambda tool, args: Decision('ask', 'manual'))
    client = MockLLMClient([
        [ToolCallComplete(label, 'Observe', {'label': label}) for label in ['approved1', 'approved2']]
        + [StreamEnd('tool_use', 1, 1)],
        [TextDelta('done'), StreamEnd('end_turn', 1, 1)],
    ])
    agent = Agent(client, registry, 'anthropic', work_dir=str(tmp_path), permission_checker=checker)
    conv = ConversationManager()
    conv.add_user_message('run')
    requests, results = [], []
    async for event in agent.run(conv):
        if isinstance(event, PermissionRequest):
            requests.append(event)
            event.future.set_result(PermissionResponse.ALLOW)
        if isinstance(event, ToolResultEvent):
            results.append(event)
    assert len(requests) == 2
    assert len(results) == 2
    assert all(not result.is_error for result in results)
    assert state.trace == [
        ('read-start', 'approved1'), ('read-end', 'approved1'),
        ('read-start', 'approved2'), ('read-end', 'approved2'),
    ]


@pytest.mark.asyncio
async def test_edit_mismatch_leaves_file_unchanged(tmp_path):
    target = tmp_path / 'settings.py'
    target.write_text('timeout = 20\n')
    registry = create_default_registry()
    agent = Agent(MockLLMClient([]), registry, 'anthropic', work_dir=str(tmp_path))
    read = await agent._execute_tool_noninteractive(ToolCallComplete('read1', 'ReadFile', {'file_path': str(target)}))
    assert not read.is_error
    edit = await agent._execute_tool_noninteractive(ToolCallComplete('edit1', 'EditFile', {
        'file_path': str(target), 'old_string': 'timeout = 10', 'new_string': 'timeout = 30',
    }))
    assert edit.is_error
    assert 'old_string not found' in edit.output
    assert target.read_text() == 'timeout = 20\n'
    assert not list((tmp_path / '.mewcode').rglob('tool-ledger.jsonl'))


@pytest.mark.asyncio
async def test_write_approval_has_basic_description_without_preview_ledger(tmp_path):
    registry = create_default_registry()
    checker = SimpleNamespace(mode=PermissionMode.DEFAULT, check=lambda tool, args: Decision('ask', 'manual'))
    target = tmp_path / 'new.txt'
    agent = Agent(MockLLMClient([]), registry, 'anthropic', work_dir=str(tmp_path), permission_checker=checker)
    call = ToolCallComplete('write1', 'WriteFile', {'file_path': str(target), 'content': 'hello'})
    result = None
    async for event in agent._execute_tool(call):
        if isinstance(event, PermissionRequest):
            assert 'Proposed diff' not in event.description
            assert '[Risk:' not in event.description
            event.future.set_result(PermissionResponse.ALLOW)
        else:
            result, _ = event
    assert result is not None and not result.is_error
    assert target.read_text() == 'hello'
    assert not list((tmp_path / '.mewcode').rglob('tool-ledger.jsonl'))
