"""Team policy and durable references to framework-owned asynchronous tasks."""

from dataclasses import dataclass, field

from pycode.core.errors import ToolError

TERMINAL_TASK_STATES = frozenset({"success", "error", "cancelled", "interrupted", "timeout"})


@dataclass(frozen=True)
class TeamAgent:
    name: str
    description: str
    graph_id: str
    url: str
    modifies_workspace: bool = False
    headers: dict[str, str] = field(default_factory=dict)


def validate_dependencies(task_id, dependencies, tasks):
    seen = set()
    for dependency in dependencies:
        if dependency == task_id or dependency in seen:
            raise ToolError("invalid_dependency", "Dependencies must be unique and acyclic")
        if dependency not in tasks:
            raise ToolError("unknown_dependency", "Dependency task does not exist: " + dependency)
        seen.add(dependency)
    visiting = set()
    visited = set()

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
    for identifier in dependencies:
        if tasks[identifier]["status"] != "success":
            return False
    return True
