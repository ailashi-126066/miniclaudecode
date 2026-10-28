"""In-process team services and Git integration without application configuration."""

from pycode.team.api import AgentTeam
from pycode.team.tools import child_tools, lead_tools
from pycode.team.worktrees import WorktreeManager


class TeamModule:
    def worktrees(self, workspace, storage_root, *, snapshot_factory):
        return WorktreeManager(workspace, storage_root, snapshot_factory=snapshot_factory)

    def create(self, ledger, history, children, worktrees, events, **settings):
        return AgentTeam(ledger, history, children, worktrees, events, **settings)

    def tools(self, member, provider):
        return lead_tools(member, provider) if member.agent_id == "lead" else child_tools(member)

    def event_relay(self, member):
        return ChildEventRelay(member)


class ChildEventRelay:
    def __init__(self, member):
        self.member = member

    async def consume(self, event):
        await self.member.team.activity(self.member.agent_id, self.member.generation, event)
