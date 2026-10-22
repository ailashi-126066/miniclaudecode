"""Team public entry. Construct via Application, with mailbox and worktree ownership.

AgentTeam: spawn, inspect/list, send, cancel, review, merge and close.
TeamMember is the scoped lead/child handle used by the common tool bindings.
"""

from pycode.team.engine import AgentTeam, TeamMember

__all__ = ["AgentTeam", "TeamMember"]
