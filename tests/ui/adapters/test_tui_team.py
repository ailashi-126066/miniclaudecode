"""Child approvals retain their source and do not acquire the busy lead executor."""

import asyncio
from types import SimpleNamespace
from unittest.mock import AsyncMock

from pycode.ui.adapters.approval import ApprovalPanel
from pycode.ui.adapters.tui import PyApp


async def test_child_approval_dispatch_while_lead_is_busy_uses_separate_task():
    panel = SimpleNamespace(disabled=False)
    host = SimpleNamespace(
        _team_tasks=set(),
        query_one=lambda *args: panel,
        answer_child=AsyncMock(),
        respond=lambda answer: (_ for _ in ()).throw(AssertionError("lead resume")),
        busy=True,
    )
    event = ApprovalPanel.Responded("op", "approve", "12345678")
    PyApp.on_approval_panel_responded(host, event)
    await asyncio.gather(*host._team_tasks)
    host.answer_child.assert_awaited_once_with(
        "12345678", {"operation_id": "op", "decision": "approve"}
    )
    assert panel.disabled


async def test_multiple_child_approvals_keep_individual_agent_identity():
    panel = SimpleNamespace(disabled=False)
    host = SimpleNamespace(
        _team_tasks=set(), query_one=lambda *args: panel, answer_child=AsyncMock()
    )
    for identifier in ("12345678", "87654321"):
        PyApp.on_approval_panel_responded(
            host, ApprovalPanel.Responded("op-" + identifier, "deny", identifier)
        )
    await asyncio.gather(*host._team_tasks)
    assert host.answer_child.await_count == 2
    assert {call.args[0] for call in host.answer_child.await_args_list} == {"12345678", "87654321"}
