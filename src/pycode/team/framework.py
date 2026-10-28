"""Native timeout and outcome routing for one background member generation."""

import time
from typing import Any, TypedDict, cast

from langgraph.errors import NodeError, NodeTimeoutError
from langgraph.graph import END, START, StateGraph
from langgraph.runtime import Runtime
from langgraph.types import Command, Send, TimeoutPolicy


class MemberState(TypedDict):
    agent_id: str
    generation: int


def member_graph(team):
    def route(state: MemberState):
        record = team.records[state["agent_id"]]
        execution = team.executions[record["task_id"]]
        execution.activity.clear()
        if record["generation"] != state["generation"]:
            return END
        if record["status"] == "failed":
            return (
                "handoff"
                if record["error_code"] in {"provider_timeout", "provider_connection"}
                and record["handoffs"] < team.max_handoffs
                else END
            )
        if record["status"] not in {"running", "needs_approval"}:
            return END
        if record["status"] == "needs_approval" or record["phase"] == "approval":
            return Send("approval", state)
        deadlines = [
            step["deadline_at"] + team.stop_timeout
            for step in record["active_steps"].values()
            if step["deadline_at"] is not None
        ]
        if deadlines:
            remaining = min(deadlines) - time.time()
            if remaining <= 0:
                return "handoff"
            return Send("wait", state, timeout=TimeoutPolicy(run_timeout=remaining))
        return Send(
            "wait",
            state,
            timeout=TimeoutPolicy(idle_timeout=team.idle_timeout, refresh_on="heartbeat"),
        )

    async def wait(state: MemberState, runtime: Runtime):
        record = team.records[state["agent_id"]]
        execution = team.executions[record["task_id"]]
        execution.heartbeat = runtime.heartbeat
        try:
            await execution.activity.wait()
        finally:
            execution.heartbeat = None
        return {}

    def timed_out(state: MemberState, error: NodeError) -> Command:
        if not isinstance(error.error, NodeTimeoutError):
            raise error.error
        record = team.records[state["agent_id"]]
        # An approval or completion can arrive while the timeout handler is scheduled.
        if record["status"] != "running" or record["phase"] == "approval":
            return Command(goto=route(state))
        return Command(goto="handoff")

    async def handoff(state: MemberState):
        record = team.records[state["agent_id"]]
        await team.handoff_task(record["task_id"], automatic=True)
        return {}

    builder = StateGraph(MemberState)
    builder.add_node("wait", wait, error_handler=cast(Any, timed_out))
    builder.add_node("approval", wait)
    builder.add_node("handoff", handoff)
    builder.add_conditional_edges(START, route)
    builder.add_conditional_edges("wait", route)
    builder.add_conditional_edges("approval", route)
    builder.add_edge("handoff", END)
    # The child owns its execution checkpoint; this graph observes live host resources.
    return builder.compile(checkpointer=False)
