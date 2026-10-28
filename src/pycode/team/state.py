"""Team business state and keyed reducers; session Store remains the durable journal."""

from typing import Annotated, TypedDict


def merge_records(current: dict, changes: dict) -> dict:
    return {**current, **changes}


def merge_agents(current: dict, changes: dict) -> dict:
    merged = dict(current)
    for key, value in changes.items():
        previous = current.get(key)
        if (
            previous is not None
            and previous["generation"] == value["generation"]
            and previous["status"] == value["status"]
            and previous.get("activity_sequence", 0) > value.get("activity_sequence", 0)
        ):
            value = {
                **value,
                **{
                    field: previous[field]
                    for field in ("activity_sequence", "last_activity_at", "phase", "active_steps")
                },
            }
        merged[key] = value
    return merged


class TeamState(TypedDict):
    agents: Annotated[dict[str, dict], merge_agents]
    tasks: Annotated[dict[str, dict], merge_records]
    mailboxes: Annotated[dict[str, dict], merge_records]


def empty_state() -> TeamState:
    return {"agents": {}, "tasks": {}, "mailboxes": {}}


def apply_transition(current: TeamState, transition: dict) -> TeamState:
    return {
        "agents": merge_agents(
            current["agents"], {row["agent_id"]: row for row in transition.get("agents", ())}
        ),
        "tasks": merge_records(
            current["tasks"], {row["task_id"]: row for row in transition.get("tasks", ())}
        ),
        "mailboxes": merge_records(
            current["mailboxes"], {row["id"]: row for row in transition.get("messages", ())}
        ),
    }
