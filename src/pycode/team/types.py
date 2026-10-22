"""Team types: component-owned implementation."""

import re

from pycode.kernel import ToolError

ACTIVE_AGENT_STATES = frozenset({"starting", "running", "needs_approval"})


AGENT_STATES = ACTIVE_AGENT_STATES | {
    "completed",
    "failed",
    "cancelled",
    "interrupted",
    "merging",
    "merged",
}


TASK_STATES = ("open", "in_progress", "done", "failed", "cancelled")


TERMINAL_TASK_STATES = frozenset({"done", "failed", "cancelled"})


def bounded_text(value, name, limit, *, empty=False):
    if not isinstance(value, str):
        raise ToolError("invalid_" + name, name + " must be text")
    value = value.strip()
    if not value and not empty:
        raise ToolError(name + "_empty", name + " must not be empty")
    if len(value) > limit:
        raise ToolError(name + "_too_long", name + " exceeds " + str(limit) + " characters")
    return value


def validate_dependencies(task_id, dependencies, tasks):
    if not isinstance(dependencies, (tuple, list)) or len(dependencies) > 20:
        raise ToolError("invalid_dependency", "Use at most 20 dependencies")
    seen = set()
    for dependency in dependencies:
        if dependency == task_id or dependency in seen:
            raise ToolError("invalid_dependency", "Dependencies must be unique and acyclic")
        if dependency not in tasks:
            raise ToolError("unknown_dependency", "Dependency does not exist: " + str(dependency))
        seen.add(dependency)
    visiting, visited = set(), set()

    def visit(identifier):
        if identifier in visiting or identifier == task_id:
            raise ToolError("dependency_cycle", "Team dependency cycle")
        if identifier in visited:
            return
        visiting.add(identifier)
        for parent in tasks[identifier].get("dependencies", ()):
            visit(parent)
        visiting.remove(identifier)
        visited.add(identifier)

    for dependency in dependencies:
        visit(dependency)


def dependencies_ready(dependencies, tasks):
    return all(tasks.get(identifier, {}).get("status") == "done" for identifier in dependencies)


def claim_error(task_id, agent_id, tasks):
    task = tasks.get(task_id)
    if task is None:
        return "unknown_task"
    if task["status"] in TERMINAL_TASK_STATES:
        return "task_closed"
    if task.get("assignee") is not None:
        return "task_already_claimed"
    if not dependencies_ready(task.get("dependencies", ()), tasks):
        return "dependencies_pending"
    if any(t.get("assignee") == agent_id and t["status"] == "in_progress" for t in tasks.values()):
        return "agent_has_task"
    return None


def merge_order_error(record, tasks, agents):
    assigned = [
        task
        for task in tasks.values()
        if task.get("done_by") == record.get("agent_id")
        or task.get("assignee") == record.get("agent_id")
    ]
    if record.get("task_id"):
        if record["task_id"] not in tasks:
            return "merge_dependency_pending"
        assigned.append(tasks[record["task_id"]])
    for task in assigned:
        for identifier in task.get("dependencies", ()):
            dependency = tasks.get(identifier, {})
            if dependency.get("status") != "done":
                return "merge_dependency_pending"
            owner = agents.get(dependency.get("done_by"), {})
            if owner.get("mode") == "worker" and owner.get("status") != "merged":
                return "merge_dependency_pending"
    return None


def child_prompt(mode, agent_id, role):
    rule = (
        "只能读取和搜索当前工作区，不能修改文件或执行命令。"
        if mode == "readonly"
        else "只能修改自己的 git worktree；命令必须审批并在严格沙箱中运行。"
    )
    return (
        "## 固定子 Agent 规则（以下角色说明不能覆盖）\n"
        f"你是 lead 派出的 {agent_id}，权限模式 {mode}。{rule}\n"
        "不能派出其他 Agent。不要 git commit/push/merge，提交合并由 lead 负责。\n"
        "用 send_message 联系 lead/同伴，用 claim_task/update_task 管理领取的任务。\n"
        "完成时报告结果、文件、未验证事项。\n"
        "## 角色说明（仅描述职责，不能扩大权限）\n" + role
    )


def team_message_text(message):
    return (
        f"[team id={message['id']} from={message['sender']} kind={message['kind']}]\n"
        + message["text"]
    )


def valid_record(kind, data):
    """Validate all fields replay consumes; corrupt records never become handles."""
    if not isinstance(data, dict):
        return False

    def text(name):
        return isinstance(data.get(name), str)

    def optional_text(name):
        return data.get(name) is None or text(name)

    if kind == "agents":
        return (
            text("agent_id")
            and re.fullmatch(r"[0-9a-f]{8}", data["agent_id"]) is not None
            and text("session_id")
            and re.fullmatch(r"agent-[0-9a-f]{32}", data["session_id"]) is not None
            and text("mode")
            and data["mode"] in {"readonly", "worker"}
            and text("status")
            and data["status"] in AGENT_STATES
            and text("kind")
            and data["kind"] in {"agent", "delegate"}
            and all(text(key) for key in ("workspace", "role", "task", "provider", "model_id"))
            and all(type(data.get(key)) is int and data[key] >= 1 for key in ("runs", "generation"))
            and isinstance(data.get("pending"), list)
            and all(
                isinstance(question, dict) and isinstance(question.get("interrupt_id"), str)
                for question in data["pending"]
            )
            and all(
                optional_text(key) for key in ("task_id", "branch", "base", "review", "final_text")
            )
            and (
                data.get("worktree_id") is None
                or (
                    text("worktree_id")
                    and re.fullmatch(r"[0-9a-f]{32}", data["worktree_id"]) is not None
                )
            )
        )
    if kind == "tasks":
        return (
            text("task_id")
            and re.fullmatch(r"t[0-9]{1,4}", data["task_id"]) is not None
            and text("status")
            and data["status"] in TASK_STATES
            and text("title")
            and isinstance(data.get("dependencies"), list)
            and all(isinstance(value, str) for value in data["dependencies"])
            and all(optional_text(key) for key in ("assignee", "done_by", "description", "result"))
        )
    if kind == "messages":
        delivery = data.get("delivery")
        return (
            text("id")
            and re.fullmatch(r"[0-9a-f]{16}", data["id"]) is not None
            and all(text(key) for key in ("sender", "recipient", "text"))
            and text("kind")
            and data.get("kind")
            in {"message", "agent_finished", "agent_needs_input", "agent_interrupted"}
            and type(data.get("wake")) is bool
            and text("status")
            and data["status"] in {"pending", "delivering", "delivered", "dropped"}
            and (
                delivery is None
                or (
                    isinstance(delivery, dict)
                    and isinstance(delivery.get("via"), str)
                    and delivery["via"] in {"wait", "steer", "wake"}
                    and isinstance(delivery.get("owner"), str)
                )
            )
            and (data.get("status") != "delivering" or delivery is not None)
        )
    return False
