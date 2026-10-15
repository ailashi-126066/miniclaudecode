"""Validate selected Python resources; SkillsMiddleware owns their disclosure."""

from pycode.kernel import EntryDraft, SessionTree, ToolError, data_hash, plain_data
from pycode.skills.domain.skills import active_skills
from pycode.skills.ports.skills import SkillCatalog, SkillResourceToolFactory


class SkillResources:
    def __init__(
        self,
        catalog: SkillCatalog,
        factory: SkillResourceToolFactory,
        entries,
        reserved_tools=(),
        history=None,
    ):
        self.catalog = catalog
        self.factory = factory
        self.entries = entries
        self.tools = {}
        self.reserved_tools = frozenset(reserved_tools)
        self.groups = {}
        self.history = history

    async def resolve_group(self, group):
        if group in self.groups:
            return self.groups[group]
        if not group.startswith("pycode_skill_"):
            return ()
        name = group.removeprefix("pycode_skill_")
        for entry in self.entries:
            if entry.name != name:
                continue
            if self.history is not None:
                snapshot = await self.history.snapshot()
                for loaded in active_skills(SessionTree(snapshot.index).path(snapshot.leaf_id)):
                    if loaded["name"] == name and (
                        loaded["content_hash"] != entry.content_hash
                        or loaded["resource_hash"] != entry.resource_hash
                    ):
                        return ()
            declarations = await self.catalog.tools(entry)
            tools = []
            for declaration in declarations:
                tool = self.factory.create(declaration, plain_data(entry))
                if tool.spec.name in self.reserved_tools:
                    raise ToolError("tool_name_conflict", "Skill tool shadows a built-in tool")
                if tool.spec.name in self.tools and self.tools[tool.spec.name].spec != tool.spec:
                    raise ToolError(
                        "tool_name_conflict",
                        "Selected Skills declare conflicting Python tool names",
                    )
                self.tools[tool.spec.name] = tool
                tools.append(tool)
            resolved = tuple(tools)
            self.groups[group] = resolved
            return resolved
        raise ToolError("unknown_skill", "Selected Skill is outside trusted sources")

    def get_tool(self, name):
        return self.tools.get(name)


class SkillLoadRecorder:
    def __init__(self, history, catalog):
        self.history = history
        self.catalog = catalog

    async def selected_entry(self, path):
        return await self.catalog.entry_at_virtual_path(path)

    async def record(self, path, result, entry_id):
        entry = await self.selected_entry(path)
        if entry is None:
            return
        declarations = await self.catalog.tools(entry)
        data = {
            **plain_data(entry),
            "message_entry_id": entry_id,
            "resource_tools": list(declarations),
            "loaded_hash": data_hash(
                {"content_hash": entry.content_hash, "resource_hash": entry.resource_hash}
            ),
        }
        snapshot = await self.history.snapshot()
        for previous in active_skills(SessionTree(snapshot.index).path(snapshot.leaf_id)):
            if previous["name"] == entry.name and previous["loaded_hash"] == data["loaded_hash"]:
                return
        await self.history.append_entry(EntryDraft("skill_load", data=data, run_id=result.run_id))
