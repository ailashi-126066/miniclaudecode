"""Trusted Skill metadata and installation; Deep Agents owns loading."""

import asyncio

from pycode.core.errors import ToolError
from pycode.core.execution_recovery import pending_run_entry
from pycode.core.revert_state import pending_revert
from pycode.core.session import EntryDraft
from pycode.core.session_tree import SessionTree
from pycode.core.skills import (
    skill_entry,
    skill_scope,
)
from pycode.core.summary_source import data_hash
from pycode.core.token_estimate import estimate_text
from pycode.core.values import plain_data
from pycode.ports.history import SessionStore
from pycode.ports.skills import SkillCatalog, SkillInstaller

CONTROL_TOOLS = frozenset({"SkillSearch"})


class SkillService:
    def __init__(
        self,
        history: SessionStore,
        catalog: SkillCatalog,
        *,
        index_budget=2000,
        installer: SkillInstaller | None = None,
    ):
        self._history = history
        self._catalog = catalog
        self._budget = index_budget
        self._installer = installer
        self._lock = asyncio.Lock()

    async def refresh(self, index=None):
        async with self._lock:
            if index is None:
                index = await self._catalog.scan()
            entries = [
                {**plain_data(entry), "allowed_tools": list(entry.allowed_tools)}
                for entry in index.entries
            ]
            digest = data_hash(entries)
            snapshot = await self._history.snapshot()
            path = SessionTree(snapshot.index).path(snapshot.leaf_id)
            previous = next(
                (entry for entry in reversed(path) if entry.type == "skill_catalog"), None
            )
            lines = [
                f"- {entry.name}: {entry.description} ({entry.source}; {entry.mode})"
                for entry in index.entries
            ]
            listing = "\n".join(lines)
            dispatch = estimate_text(listing) > self._budget
            data = {
                "entries": entries,
                "hash": digest,
                "text": "",
                "dispatch": dispatch,
                "issues": list(index.issues),
            }
            if previous is not None and plain_data(previous.data) == data:
                return previous.data
            saved = await self._history.append_entry(EntryDraft("skill_catalog", data=data))
            return saved.data

    async def index(self):
        snapshot = await self._history.snapshot()
        path = SessionTree(snapshot.index).path(snapshot.leaf_id)
        entry = next((item for item in reversed(path) if item.type == "skill_catalog"), None)
        if entry is None:
            if pending_run_entry(snapshot) is not None or pending_revert(snapshot) is not None:
                raise ToolError(
                    "skill_catalog_unavailable", "Recover execution before creating a catalog"
                )
            return await self.refresh()
        return entry.data

    async def search(self, query, limit=10):
        if not isinstance(query, str) or not isinstance(limit, int) or isinstance(limit, bool):
            raise ToolError("invalid_skill_search", "Search requires text and an integer limit")
        if not 1 <= limit <= 20:
            raise ToolError("invalid_skill_search", "Skill search limit must be between 1 and 20")
        terms = query.casefold().split()
        found = []
        for entry in (await self.index())["entries"]:
            text = (entry["name"] + " " + entry["description"]).casefold()
            score = sum(term in text for term in terms)
            if not terms or score:
                found.append((score, entry))
        found.sort(key=lambda item: (-item[0], item[1]["name"]))
        results = []
        for _, entry in found[:limit]:
            result = {}
            for key in ("name", "description", "source", "mode"):
                result[key] = entry[key]
            result["path"] = self._catalog.virtual_path(skill_entry(entry))
            results.append(result)
        return tuple(results)

    async def request(self, name, args=""):
        if not isinstance(name, str) or not isinstance(args, str) or len(args) > 16000:
            raise ToolError("invalid_skill_arguments", "Skill arguments must be bounded text")
        index = await self._catalog.scan()
        for entry in index.entries:
            if entry.name == name:
                return {
                    "name": name,
                    "path": self._catalog.virtual_path(entry),
                    "arguments": args,
                    "mode": entry.mode,
                    "context": entry.context,
                }
        raise ToolError("unknown_skill", "Skill is outside the trusted catalog")

    async def install(self, url, *, ref=None, subpath=None):
        if self._installer is None:
            raise ToolError("skill_install_unavailable", "This session has no Skill installer")
        report = await self._installer.install(url, ref=ref, subpath=subpath)
        catalog = await self.refresh()
        saved = await self._history.append_entry(
            EntryDraft("skill_install", data={**report, "catalog_hash": catalog["hash"]})
        )
        return saved.data


class SkillToolGate:
    def __init__(self, history, parent):
        self._history = history
        self._parent = parent

    async def authorize(self, context, spec, call):
        snapshot = await self._history.snapshot()
        allowed = skill_scope(SessionTree(snapshot.index).path(snapshot.leaf_id))
        if allowed is not None:
            aliases = {
                "Read": "read_file",
                "Write": "write_file",
                "Edit": "edit_file",
                "Grep": "grep",
                "Glob": "glob",
            }
            allowed = {aliases.get(name, name) for name in allowed}
            skill_read = spec.name == "read_file" and call.arguments.get(
                "file_path", ""
            ).startswith("/skills/")
            if spec.name not in allowed and spec.name not in CONTROL_TOOLS and not skill_read:
                raise ToolError("skill_tool_not_allowed", "Active Skill scope excludes this tool")
        await self._parent.authorize(context, spec, call)
