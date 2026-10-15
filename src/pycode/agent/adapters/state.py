"""Private graph state owned by the agent runtime."""

from deepagents.graph import DeepAgentState


class PycodeState(DeepAgentState):
    session_id: str
    run_id: str
    branch_leaf_id: str | None
    turn: int
    outcome: str
    final_text: str
