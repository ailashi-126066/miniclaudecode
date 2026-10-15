"""In-process team services and Git integration without application configuration."""

from pycode.team.adapters.delegate import TeamDelegate
from pycode.team.adapters.team_tools import child_tools, lead_tools
from pycode.team.adapters.worktrees import WorktreeManager
from pycode.team.services.team import AgentTeam


class TeamModule:
    def worktrees(self, workspace, storage_root, *, snapshot_factory):
        return WorktreeManager(workspace, storage_root, snapshot_factory=snapshot_factory)

    def create(self, ledger, history, children, worktrees, events, **settings):
        return AgentTeam(ledger, history, children, worktrees, events, **settings)

    def tools(self, member, provider):
        return lead_tools(member, provider) if member.agent_id == "lead" else child_tools(member)

    def selector_required_names(self, member, provider) -> tuple[str, ...]:
        return tuple(tool.spec.name for tool in self.tools(member, provider))

    def delegate(self, team, provider, parent_history, run_id=None):
        return TeamDelegate(team, provider, parent_history, run_id)

    def event_relay(self, member):
        return ChildEventRelay(member)


class ChildEventRelay:
    def __init__(self, member):
        self.member = member

    async def consume(self, event):
        if event.kind in {"error", "tool_execution_start"}:
            record = self.member.team.records[self.member.agent_id]
            await self.member.team.emit(
                "activity",
                {
                    "agent_id": self.member.agent_id,
                    "role": record["role"],
                    "event": event.kind,
                    "data": event.data,
                },
            )
