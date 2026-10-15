"""Local team controls exposed through the ordinary tool executor and settlement."""

from pycode.core.errors import StorageError, ToolError
from pycode.core.messages import ToolResult
from pycode.core.tools import ToolSpec
from pycode.core.values import json_dumps

_TEXT = {"type": "string"}
_AGENT = {"type": "string", "pattern": "^[0-9a-f]{8}$"}
_TASK = {"type": "string", "pattern": "^t[0-9]{1,4}$"}
_DEPS = {"type": "array", "items": _TASK, "maxItems": 20}


def schema(properties, required=()):
    return {
        "type": "object",
        "properties": properties,
        "required": list(required),
        "additionalProperties": False,
    }


class TeamTool:
    def __init__(self, member, name, properties=None, required=(), *, provider=None):
        self.member, self.provider = member, provider
        self.spec = ToolSpec(
            name,
            "Local team: " + name.replace("_", " "),
            schema(properties or {}, required),
            category="write" if name == "merge_agent" else "read",
            source="team",
            execution_mode="parallel"
            if name in {"list_agents", "list_tasks", "review_agent"}
            else "sequential",
        )

    async def execute(self, context, arguments):
        team, actor, name = self.member.team, self.member.agent_id, self.spec.name
        try:
            result = await self.dispatch(team, actor, name, context, arguments)
        except StorageError as error:
            raise ToolError("team_storage_error", str(error)) from error
        return ToolResult(
            context.call_id,
            name,
            json_dumps(result, ensure_ascii=False),
            structured_content=result,
            is_error=bool(result.get("error_code")),
            error_code=result.get("error_code"),
            details={"team_message_ids": [m["id"] for m in result.get("messages", ())]}
            if name == "wait_agents"
            else {},
        )

    async def dispatch(self, team, actor, name, context, args):
        if name == "spawn_agent":
            return await team.spawn(**args, provider=self.provider, run_id=context.run_id)
        if name == "send_message":
            return await team.inbox.send(actor, args["to"], args["text"], context.run_id)
        if name == "wait_agents":
            return await team.inbox.wait(**args, operation_id=context.operation_id)
        if name == "cancel_agent":
            return await team.cancel(args["id"])
        if name == "list_agents":
            return team.agents_view()
        if name == "create_task":
            return await team.board.create_task(**args)
        if name == "claim_task":
            return await team.board.claim_task(args["task_id"], actor)
        if name == "update_task":
            return await team.board.update_task(actor=actor, **args)
        if name == "list_tasks":
            return {"tasks": list((await team.board.tasks()).values())}
        if name == "review_agent":
            return await team.review(args["id"])
        if name == "merge_agent":
            preview = team.previews.pop(context.operation_id)
            return await team.merge(args["id"], preview, args.get("action", "merge"))
        raise AssertionError("Unregistered team tool")


def child_tools(member):
    return (
        TeamTool(member, "send_message", {"to": _TEXT, "text": _TEXT}, ("to", "text")),
        TeamTool(member, "list_agents"),
        TeamTool(member, "list_tasks"),
        TeamTool(member, "claim_task", {"task_id": _TASK}, ("task_id",)),
        TeamTool(
            member,
            "update_task",
            {
                "task_id": _TASK,
                "status": {"enum": ["in_progress", "done", "failed"]},
                "result": _TEXT,
            },
            ("task_id", "status"),
        ),
    )


def lead_tools(member, provider):
    basic = tuple(
        tool
        for tool in child_tools(member)
        if tool.spec.name != "claim_task" and tool.spec.name != "update_task"
    )
    return (
        *basic,
        TeamTool(
            member,
            "spawn_agent",
            {
                "mode": {"enum": ["readonly", "worker"]},
                "role": _TEXT,
                "task": _TEXT,
                "task_id": _TASK,
            },
            ("mode", "role", "task"),
            provider=provider,
        ),
        TeamTool(
            member,
            "wait_agents",
            {
                "ids": {"type": "array", "items": _AGENT, "maxItems": 64},
                "timeout": {"type": "integer", "minimum": 1, "maximum": 600},
            },
        ),
        TeamTool(member, "cancel_agent", {"id": _AGENT}, ("id",)),
        TeamTool(
            member,
            "create_task",
            {"title": _TEXT, "description": _TEXT, "depends_on": _DEPS},
            ("title",),
        ),
        TeamTool(
            member,
            "update_task",
            {
                "task_id": _TASK,
                "title": _TEXT,
                "description": _TEXT,
                "status": {"enum": ["open", "in_progress", "done", "failed", "cancelled"]},
                "result": _TEXT,
                "depends_on": _DEPS,
            },
            ("task_id",),
        ),
        TeamTool(member, "review_agent", {"id": _AGENT}, ("id",)),
        TeamTool(
            member,
            "merge_agent",
            {"id": _AGENT, "action": {"enum": ["merge", "prepare_resolution"]}},
            ("id",),
        ),
    )
