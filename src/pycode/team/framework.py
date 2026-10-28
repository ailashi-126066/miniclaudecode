"""A model-free LangGraph transition uses the TeamState reducers."""

from langgraph.graph import END, START, StateGraph

from pycode.team.state import TeamState


class TransitionState(TeamState):
    transition: dict


def apply_transition(state: TransitionState):
    transition = state["transition"]
    return {
        field: {record[key]: record for record in transition.get(group, ())}
        for group, field, key in (
            ("agents", "agents", "agent_id"),
            ("tasks", "tasks", "task_id"),
            ("messages", "mailboxes", "id"),
        )
    }


def transition_graph():
    builder = StateGraph(TransitionState)
    builder.add_node("apply", apply_transition)
    builder.add_edge(START, "apply")
    builder.add_edge("apply", END)
    # Called from tool/model callbacks: never inherit their task checkpoint.
    graph = builder.compile(checkpointer=False)

    def reduce(current: TeamState, transition: dict) -> TeamState:
        updated = graph.invoke({**current, "transition": transition})
        return {
            "agents": updated["agents"],
            "tasks": updated["tasks"],
            "mailboxes": updated["mailboxes"],
        }

    return reduce
