"""Official LangMem tools use the existing domain tool and settlement boundaries."""

from langmem import create_manage_memory_tool, create_search_memory_tool

from pycode.core.errors import ModelError, ToolError
from pycode.core.memory_schema import MemoryValue
from pycode.core.tools import ToolSpec


def official_memory_tool(namespace, store, *, manage, name):
    if manage:
        return create_manage_memory_tool(
            namespace,
            schema=MemoryValue,
            store=store,
            name=name,
            instructions=(
                "Create, update or forget durable scoped memory with exact source IDs and quotes. "
                "Use current user or actual tool evidence. Use current for session_id and entry_id "
                "when the runtime should resolve an exact current-task quote. "
                "Exclude secrets and temporary requests. "
                "Historical cases are references; a chat ending is not verified success. "
                "Rules must reflect explicit lasting user instructions or a recorded comparison."
            ),
        )
    return create_search_memory_tool(
        namespace,
        store=store,
        name=name,
        response_format="content_and_artifact",
        instructions=(
            "Search scoped memory when current context is insufficient. Use only relevant results."
        ),
    )


class ScopedMemoryTool:
    def __init__(self, service, namespace, *, manage):
        self.service, self.namespace, self.manage = service, namespace, manage
        scope = service.scopes[namespace]
        name = ("manage_" if manage else "search_") + scope + "_memory"
        native = official_memory_tool(namespace, service.store, manage=manage, name=name)
        self.spec = ToolSpec(
            name,
            native.description,
            native.args_schema.model_json_schema(),
            category="write" if manage else "read",
            execution_mode="sequential",
            source="memory",
            approval_details={"memory_namespace": list(namespace)},
        )

    async def execute(self, context, arguments):
        try:
            return await self.service.active_operation(
                context,
                self.namespace,
                arguments,
                manage=self.manage,
                name=self.spec.name,
            )
        except ModelError as error:
            raise ToolError(
                "memory_model_failed", "Memory verification or search failed: " + error.code
            ) from error


def memory_tools(service, *, read_only=False):
    if service is None or service.mode == "off" or service.updater is None:
        return ()
    tools = []
    for namespace in service.scopes:
        tools.append(ScopedMemoryTool(service, namespace, manage=False))
        if not read_only:
            tools.append(ScopedMemoryTool(service, namespace, manage=True))
    return tuple(tools)
