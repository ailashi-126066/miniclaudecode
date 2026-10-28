"""Compact local team status, task board and retained worktree references."""

import time

from textual.app import ComposeResult
from textual.containers import VerticalScroll
from textual.widgets import Static


class AgentsPanel(VerticalScroll):
    DEFAULT_CSS = "AgentsPanel {height: auto; max-height: 20; border: solid $primary; padding: 1;}"

    def __init__(self, report):
        super().__init__(id="agents-panel")
        self.report = report or {"agents": [], "tasks": [], "running": 0, "limit": 4}

    def compose(self) -> ComposeResult:
        yield Static(f"Agents {self.report['running']}/{self.report['limit']}", markup=False)
        for agent in self.report["agents"]:
            activity = ""
            if agent.get("last_activity_at") is not None:
                elapsed = max(0, int(time.time() - agent["last_activity_at"]))
                activity = f"\n{agent.get('phase', '')} · last activity {elapsed}s ago"
            yield Static(
                f"{agent['agent_id']} · {agent['mode']} · {agent['status']} · "
                f"{agent['role'][:120]}\n" + str(agent.get("workspace", "")) + activity,
                markup=False,
            )
        for task in self.report["tasks"]:
            yield Static(
                f"{task['task_id']} · {task['status']} · "
                f"{task.get('assignee') or 'unclaimed'} · {task['title']}"
                + (
                    " · blocked by " + ", ".join(task["blocked_by"])
                    if task.get("blocked_by")
                    else ""
                ),
                markup=False,
            )
