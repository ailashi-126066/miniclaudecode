"""Official LangMem tools use the existing domain tool and settlement boundaries."""

from langmem import create_manage_memory_tool, create_search_memory_tool

from pycode.core.errors import ToolError
from pycode.core.memory import memory_record
from pycode.core.messages import ToolResult
from pycode.core.tools import ToolSpec
from pycode.core.values import json_dumps, plain_data


def official_memory_tool(namespace, store, *, manage, name):
    if manage:
        return create_manage_memory_tool(
            namespace,
            store=store,
            name=name,
            instructions=(
                "Create, update or delete one short durable memory: lasting user preferences, "
                "corrections of your behavior or stable project facts. Skip temporary requests. "
                "Never store secrets, tokens, passwords or keys."
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


def item_record(item, scope):
    record = {
        "value": item.value,
        "created_at": item.created_at.isoformat(),
        "updated_at": item.updated_at.isoformat(),
    }
    return memory_record(item.namespace, item.key, record, scope)


class ScopedMemoryTool:
    def __init__(self, service, namespace, *, manage):
        self.service, self.namespace, self.manage = service, namespace, manage
        self.scope = service.scopes[namespace]
        name = ("manage_" if manage else "search_") + self.scope + "_memory"
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
        name = self.spec.name

        async def operate(store):
            native = official_memory_tool(self.namespace, store, manage=self.manage, name=name)
            if self.manage:
                return await native.ainvoke(arguments)
            return await native.ainvoke(
                {"type": "tool_call", "id": context.call_id, "name": name, "args": arguments}
            )

        try:
            response, changes = await self.service.tool_operation(context, self.namespace, operate)
        except ValueError as error:
            # LangMem rejects invalid action/id combinations with ValueError.
            raise ToolError("invalid_memory_arguments", str(error)[:300]) from error
        details = {"memory_namespace": list(self.namespace)}
        if self.manage:
            details["memory_ids"] = [change["key"] for change in changes]
            details["memory_deleted"] = [c["key"] for c in changes if c["after"] is None]
            return ToolResult("", "", str(response), details=details)
        records = plain_data([item_record(item, self.scope) for item in response.artifact or ()])
        details["memory_records"] = records
        return ToolResult("", "", json_dumps(records, ensure_ascii=False), details=details)


def memory_tools(service, *, read_only=False):
    if service is None or service.mode == "off":
        return ()
    tools = []
    for namespace in service.scopes:
        tools.append(ScopedMemoryTool(service, namespace, manage=False))
        if not read_only:
            tools.append(ScopedMemoryTool(service, namespace, manage=True))
    return tuple(tools)
