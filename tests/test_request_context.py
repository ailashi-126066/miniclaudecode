"""Request views preserve history, frozen decisions, and recoverable result files."""
import copy

import pytest

from mewcode.agent import Agent
from mewcode.context import (
    append_replacement_records,
    apply_tool_result_budget,
    clone_replacement_state,
    create_replacement_state,
    load_replacement_records,
    reconstruct_replacement_state,
)
from mewcode.context.manager import PERSISTED_TAG, SNIPPED_TAG, auto_compact
from mewcode.conversation import ConversationManager, Message, ToolResultBlock, ToolUseBlock
from mewcode.tools import ToolRegistry
from test_agent import MockLLMClient
from test_context import _make_long_conversation, _SummaryClient


def results(*sizes):
    return Message('user', '', tool_results=[
        ToolResultBlock(f't{i}', 'x' * size) for i, size in enumerate(sizes)
    ])


def test_exact_single_limit_and_request_metadata(tmp_path):
    conv = ConversationManager([results(50_000, 50_001)])
    conv.env_injected = True
    conv.ltm_injected = True
    conv.record_usage_anchor(123, 45, 67, 89)
    before = copy.deepcopy(conv.history)
    api, records = apply_tool_result_budget(conv, tmp_path, create_replacement_state())
    assert api is not conv
    assert api.history[0].tool_results[0].content == 'x' * 50_000
    assert api.history[0].tool_results[1].content.startswith(PERSISTED_TAG)
    assert conv.history == before
    assert (api.env_injected, api.ltm_injected, api.baseline_tokens, api.anchor_count) == (
        conv.env_injected, conv.ltm_injected, conv.baseline_tokens, conv.anchor_count
    )
    assert [r.tool_use_id for r in records] == ['t1']


def test_aggregate_applies_per_message_not_whole_conversation(tmp_path):
    a, b = results(40_000, 40_000, 40_000), results(40_000, 40_000, 40_000)
    for tr in b.tool_results:
        tr.tool_use_id += '_b'
    conv = ConversationManager([a, b])
    api, records = apply_tool_result_budget(conv, tmp_path, create_replacement_state())
    assert api.history == conv.history
    assert records == []
    assert sum(len(tr.content) for m in api.history for tr in m.tool_results) == 240_000


def test_repeated_request_and_restore_keep_frozen_decisions(tmp_path):
    conv = ConversationManager([results(45_000, 45_000, 45_001, 45_000, 45_000)])
    state = create_replacement_state()
    api, records = apply_tool_result_budget(conv, tmp_path, state)
    append_replacement_records(tmp_path, records)
    again, new_records = apply_tool_result_budget(conv, tmp_path, state)
    assert again.history == api.history
    assert new_records == []
    recovered = reconstruct_replacement_state(conv.history, load_replacement_records(tmp_path))
    restored, new_records = apply_tool_result_budget(conv, tmp_path, recovered)
    assert restored.history == api.history
    assert new_records == []
    child = clone_replacement_state(state)
    child.seen_ids.add('child-only')
    child.replacements['child-only'] = 'child'
    assert 'child-only' not in state.seen_ids
    assert 'child-only' not in state.replacements


def turns(count):
    conv = ConversationManager()
    for i in range(count):
        conv.history.extend([
            Message('user', f'task {i}'),
            Message('assistant', '', tool_uses=[ToolUseBlock(f'read_{i}', 'ReadFile', {'file_path': 'login.py'})]),
            Message('user', '', tool_results=[ToolResultBlock(f'read_{i}', 'z' * 8_000)]),
            Message('assistant', f'answer {i}'),
        ])
    return conv


@pytest.mark.parametrize('count,old_snipped', [(10, False), (11, True)])
def test_stale_turn_rule_only_changes_request(count, old_snipped, tmp_path):
    conv = turns(count)
    before = copy.deepcopy(conv.history)
    api, records = apply_tool_result_budget(conv, tmp_path, create_replacement_state())
    assert api.history[2].tool_results[0].content.startswith(SNIPPED_TAG) == old_snipped
    assert api.history[-2].tool_results[0].content == 'z' * 8_000
    assert conv.history == before
    assert records == []
    assert not list(tmp_path.glob("*.txt"))


def test_tool_calls_do_not_count_as_completed_turns(tmp_path):
    conv = ConversationManager()
    for i in range(20):
        conv.history.extend([
            Message('assistant', '', tool_uses=[ToolUseBlock(f't{i}', 'Grep', {})]),
            Message('user', '', tool_results=[ToolResultBlock(f't{i}', 'x' * 3_000)]),
        ])
    api, _ = apply_tool_result_budget(conv, tmp_path, create_replacement_state())
    assert api.history == conv.history


def test_structured_content_survives_unmodified_but_not_replaced_results(tmp_path):
    blocks = [{'type': 'image', 'source': {'type': 'base64', 'media_type': 'image/png', 'data': 'fake'}}]
    conv = ConversationManager([Message('user', '', tool_results=[
        ToolResultBlock('image', 'image content', content_blocks=blocks),
        ToolResultBlock('large', 'x' * 60_000, content_blocks=[{'type': 'text', 'text': 'x' * 60_000}]),
    ])])
    api, _ = apply_tool_result_budget(conv, tmp_path, create_replacement_state())
    assert api.history[0].tool_results[0].content_blocks == blocks
    assert api.history[0].tool_results[1].content_blocks is None
    assert conv.history[0].tool_results[1].content_blocks is not None


def test_agent_restores_decisions_and_isolates_sessions(tmp_path):
    conv = ConversationManager([results(45_000, 45_000, 45_001, 45_000, 45_000)])
    agent = Agent(MockLLMClient([]), ToolRegistry(), 'anthropic', work_dir=str(tmp_path))
    agent.session_id = 'first'
    first = agent._prepare_api_conversation(conv)
    assert 't2' in agent.replacement_state.replacements
    resumed = Agent(MockLLMClient([]), ToolRegistry(), 'anthropic', work_dir=str(tmp_path))
    resumed.session_id = 'first'
    assert resumed._prepare_api_conversation(copy.deepcopy(conv)).history == first.history
    assert len(load_replacement_records(resumed.session_dir)) == 1
    agent.session_id = 'second'
    new = ConversationManager([results(100)])
    assert agent._prepare_api_conversation(new).history == new.history
    assert not agent.replacement_state.replacements
    assert not load_replacement_records(agent.session_dir)


def test_clear_and_rewind_prune_only_removed_decisions(tmp_path):
    agent = Agent(MockLLMClient([]), ToolRegistry(), 'anthropic', work_dir=str(tmp_path))
    conv = ConversationManager([results(60_000)])
    agent._prepare_api_conversation(conv)
    conv.replace_history([])
    agent._prepare_api_conversation(conv)
    assert agent.replacement_state.seen_ids == set()
    assert agent.replacement_state.replacements == {}


@pytest.mark.asyncio
async def test_compaction_keeps_output_files_and_replacement_log(tmp_path):
    conv = _make_long_conversation()
    (tmp_path / 'old_result.txt').write_text('full output')
    (tmp_path / 'replacement_records.jsonl').write_text('retained records')
    conv.record_usage_anchor(200_000)
    await auto_compact(conv, _SummaryClient(), 200_000, tmp_path)
    assert (tmp_path / 'old_result.txt').read_text() == 'full output'
    assert (tmp_path / 'replacement_records.jsonl').read_text() == 'retained records'
