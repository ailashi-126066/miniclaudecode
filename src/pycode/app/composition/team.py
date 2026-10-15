"""Composition of team capabilities; concrete adapters stay at this boundary."""

import asyncio
import hashlib
from dataclasses import replace

from pycode.app.team_runtime import child_config
from pycode.kernel import EventStream, StorageError
from pycode.session.adapters.storage.layout import StorageLayout
from pycode.snapshot.adapters.git_tree import GitSnapshotStore
from pycode.team import AgentTeam, child_prompt
from pycode.team.adapters.worktrees import WorktreeManager


class ChildEventRelay:
    def __init__(self, member):
        self.member = member

    async def consume(self, event):
        if event.kind in {"error", "tool_execution_start"}:
            record = self.member.team.records[self.member.agent_id]
            await self.member.team.emit(
                "activity",
                {
                    "agent_id": self.member.agent_id,
                    "role": record["role"],
                    "event": event.kind,
                    "data": event.data,
                },
            )


class ConfiguredChildSessions:
    def __init__(self, config, providers, catalog, *, session_opener, repository_factory):
        self.config, self.providers, self.catalog = config, providers, catalog
        self.session_opener, self.repository_factory = session_opener, repository_factory
        self.workspace = str(config.workspace)

    async def open(self, record, member, *, create, fork_from=None, fork_entry_ids=None):
        config = child_config(self.config, record)
        if record["mode"] == "worker":
            owned = await build_worktree_manager(self.config).inspect(record["worktree_id"])
            if owned["path"] != str(config.workspace) or owned.get("missing"):
                raise StorageError(
                    "invalid_worktree_record", "Child workspace does not match owned worktree"
                )
        provider = self.catalog.resolve(record["provider"], record["model_id"])
        if fork_from is not None:
            inherited = await self.repository_factory(config).fork(*fork_from, record["session_id"])
            await inherited.aclose()
        session = await self.session_opener(
            config,
            (provider,),
            record["session_id"],
            create=create and fork_from is None,
            consumers=(ChildEventRelay(member),),
            refresh_models=False,
            startup_hooks=False,
            connect_mcp=False,
            team_member=member,
            prompt_suffix=child_prompt(record["mode"], record["agent_id"], record["role"]),
        )
        if fork_entry_ids:
            await session.configure_fork_context(fork_entry_ids)
        return session

    async def input_accepted(self, record, input_id):
        try:
            store = await self.repository_factory(child_config(self.config, record)).open(
                record["session_id"]
            )
        except StorageError as error:
            if error.code in {"session_not_found", "session_missing", "session_quarantined"}:
                return False
            raise
        try:
            return any(
                entry.message is not None and entry.message.id == input_id
                for entry in (await store.snapshot()).entries
            )
        finally:
            await store.aclose()


async def build_team(
    config,
    providers,
    catalog,
    store,
    consumers,
    event_journal,
    plans,
    *,
    session_opener,
    repository_factory,
):
    snapshot = await store.snapshot()
    identifier = "team-" + hashlib.sha256(snapshot.header.session_id.encode()).hexdigest()[:32]
    ledger = await repository_factory(config).open(identifier, create=True)
    try:
        await ledger.claim_writer()
        layout = StorageLayout(config.workspace, config.user_data)
        child_settings = replace(
            config, request_database=config.request_database or layout.runtime_database
        )
        worktrees = build_worktree_manager(config)
        try:
            _, git_status = await asyncio.to_thread(
                worktrees.git, ["rev-parse", "--show-toplevel"], allowed=(0, 128)
            )
        except OSError:
            git_status = 128
        if git_status != 0:
            worktrees = None
        return AgentTeam(
            ledger,
            store,
            ConfiguredChildSessions(
                child_settings,
                providers,
                catalog,
                session_opener=session_opener,
                repository_factory=repository_factory,
            ),
            worktrees,
            EventStream(snapshot.header.session_id, "team", consumers, journal=event_journal),
            max_agents=config.team_max_agents,
            plans=plans,
            lead_read_only=config.read_only,
        )
    except BaseException:
        await ledger.aclose()
        raise


def build_worktree_manager(config):
    layout = StorageLayout(config.workspace, config.user_data)
    return WorktreeManager(
        config.workspace, layout.project / "worktrees", snapshot_factory=GitSnapshotStore
    )
