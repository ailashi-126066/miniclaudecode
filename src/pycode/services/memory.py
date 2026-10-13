"""Evidence-backed memory review and bounded selection for fresh user tasks."""

import asyncio
import contextvars
from dataclasses import replace
from uuid import uuid4

from pycode.core.errors import ModelError, StorageError, ToolError
from pycode.core.memory import (
    MemoryCandidate,
    MemoryRecord,
    content_hash,
    expected_scope,
    validate_candidate,
)
from pycode.core.messages import Message
from pycode.core.session import EntryDraft
from pycode.core.session_tree import SessionTree
from pycode.core.summary_source import data_hash
from pycode.core.values import plain_data


class MemoryService:
    def __init__(self, history, store, timing, *, extractor=None, mode="auto", max_selected=5):
        self.history = history
        self.store = store
        self.timing = timing
        self.extractor = extractor
        self.mode = mode
        self.max_selected = max_selected
        self.tasks = {}
        self.lock = asyncio.Lock()
        self.configured_mode = mode

    async def initialize(self):
        if self.configured_mode is None:
            self.mode = await self.store.get_mode()
        if self.mode not in {"auto", "review", "off"}:
            raise StorageError("invalid_memory_mode", "Persisted memory mode is invalid")

    async def set_mode(self, mode):
        await self.store.set_mode(mode)
        self.mode = mode
        if mode == "off":
            await self.history.append_entry(
                EntryDraft("memory_load", data={"records": [], "loaded_at": self.timing.now()})
            )

    async def list(self):
        return {
            "mode": self.mode,
            "records": plain_data(await self.store.list_records()),
            "candidates": plain_data(await self.store.candidates()),
        }

    async def ingest(self, values, snapshot, *, model="configured"):
        async with self.lock:
            source_entries = {}
            for entry in SessionTree(snapshot.index).path(snapshot.leaf_id):
                source_entries[entry.id] = entry
            prefix_hash = data_hash([plain_data(entry) for entry in source_entries.values()])
            accepted = []
            for value in values:
                category = value.get("category", "")
                candidate = MemoryCandidate(
                    uuid4().hex,
                    value.get("action", "add"),
                    value.get("key", ""),
                    category,
                    value.get("scope", expected_scope(category)),
                    value.get("content", ""),
                    tuple(value.get("evidence", ())),
                    snapshot.header.session_id,
                    prefix_hash,
                    model,
                    self.timing.now(),
                    value.get("target_id"),
                    value.get("durable", True),
                )
                try:
                    validate_candidate(candidate, source_entries)
                except ToolError as error:
                    await self.history.append_entry(
                        EntryDraft(
                            "memory_rejected",
                            data={
                                "candidate_id": candidate.candidate_id,
                                "reason": error.code,
                                "prefix_hash": prefix_hash,
                            },
                        )
                    )
                    continue
                index = await self.store.list_index()
                existing = None
                duplicate = None
                for item in index:
                    if (
                        item["sha256"] == content_hash(candidate.content)
                        and item["category"] == candidate.category
                        and item["scope"] == candidate.scope
                    ):
                        duplicate = item
                    if item["memory_id"] == candidate.target_id or (
                        item["key"] == candidate.key and item["scope"] == candidate.scope
                    ):
                        existing = item
                if duplicate is not None and candidate.action == "add":
                    record = await self.store.read_record(duplicate["memory_id"])
                    evidence = list(record.evidence)
                    for item in candidate.evidence:
                        enriched = {
                            **plain_data(item),
                            "source_session_id": snapshot.header.session_id,
                        }
                        if enriched not in evidence:
                            evidence.append(enriched)
                    await self.store.write(
                        replace(record, evidence=tuple(evidence), updated_at=self.timing.now()),
                        expected_hash=record.sha256,
                    )
                    await self.history.append_entry(
                        EntryDraft(
                            "memory_merged",
                            data={
                                "memory_id": duplicate["memory_id"],
                                "evidence": plain_data(candidate.evidence),
                                "prefix_hash": prefix_hash,
                            },
                        )
                    )
                    accepted.append(duplicate["memory_id"])
                    continue
                reason = None
                if candidate.scope != expected_scope(candidate.category):
                    reason = "scope_ambiguous"
                elif candidate.action in {"update", "remove"} or existing is not None:
                    reason = "conflict_requires_review"
                if existing is not None:
                    candidate = replace(
                        candidate,
                        target_id=existing["memory_id"],
                        metadata={"expected_hash": existing["sha256"]},
                    )
                candidate = replace(candidate, reason=reason)
                await self.store.save_candidate(candidate)
                await self.history.append_entry(
                    EntryDraft(
                        "memory_candidate",
                        data={
                            "candidate_id": candidate.candidate_id,
                            "category": candidate.category,
                            "scope": candidate.scope,
                            "reason": reason,
                            "prefix_hash": prefix_hash,
                        },
                    )
                )
                if self.mode == "auto" and reason is None:
                    accepted.append(await self.apply(candidate))
            return tuple(accepted)

    async def apply(self, candidate):
        if candidate.action == "remove":
            if candidate.target_id is None:
                raise StorageError(
                    "memory_target_missing", "Choose an existing memory before removal"
                )
            await self.store.remove(
                candidate.target_id, expected_hash=candidate.metadata.get("expected_hash")
            )
            identifier = candidate.target_id
        else:
            identifier = candidate.target_id or uuid4().hex
            created = self.timing.now()
            moving = None
            expected_hash = candidate.metadata.get("expected_hash")
            if candidate.target_id is not None:
                previous = await self.store.read_record(candidate.target_id)
                created = previous.created_at
                if previous.scope != candidate.scope:
                    if previous.sha256 != expected_hash:
                        raise StorageError(
                            "memory_version_conflict", "Memory changed before scope review"
                        )
                    identifier = candidate.candidate_id
                    moving = previous
                    expected_hash = None
            record = MemoryRecord(
                identifier,
                candidate.key,
                candidate.category,
                candidate.scope,
                candidate.content,
                created,
                self.timing.now(),
                tuple(
                    {**plain_data(item), "source_session_id": candidate.source_session_id}
                    for item in candidate.evidence
                ),
                content_hash(candidate.content),
            )
            await self.store.write(record, expected_hash=expected_hash)
            if moving is not None:
                await self.store.remove(moving.memory_id, expected_hash=moving.sha256)
                await self.history.append_entry(
                    EntryDraft(
                        "memory_scope_change",
                        data={
                            "old_memory_id": moving.memory_id,
                            "memory_id": identifier,
                            "old_scope": moving.scope,
                            "scope": candidate.scope,
                        },
                    )
                )
        await self.store.review(candidate.candidate_id, "approved", self.timing.now())
        await self.history.append_entry(
            EntryDraft(
                "memory_applied",
                data={
                    "candidate_id": candidate.candidate_id,
                    "memory_id": identifier,
                    "action": candidate.action,
                },
            )
        )
        return identifier

    async def review(self, candidate_id, decision, *, content=None, scope=None):
        candidate = None
        for item in await self.store.candidates():
            if item.candidate_id == candidate_id and item.status == "pending":
                candidate = item
                break
        if candidate is None:
            raise StorageError(
                "memory_candidate_not_found", "No pending candidate has this identity"
            )
        if decision == "reject":
            await self.store.review(candidate_id, "rejected", self.timing.now())
            return None
        if decision != "approve":
            raise StorageError("invalid_memory_review", "Choose approve or reject")
        if content is not None:
            candidate = replace(candidate, content=content)
        if scope is not None:
            candidate = replace(candidate, scope=scope)
        if candidate.scope not in {"user", "project"}:
            raise StorageError("invalid_memory_scope", "Choose user or project memory")
        source = await self.history.snapshot()
        entries = {}
        for entry in source.entries:
            entries[entry.id] = entry
        validate_candidate(candidate, entries)
        return await self.apply(candidate)

    async def select(self, query):
        if self.mode == "off":
            return ()
        index = await self.store.list_index()
        words = query.casefold().split()
        for offset in range(len(query) - 1):
            pair = query[offset : offset + 2]
            if any(ord(character) > 127 for character in pair):
                words.append(pair.casefold())
        ranked = []
        for item in index:
            text = (item["key"] + " " + item["summary"]).casefold()
            score = 2 if item["category"] in {"user_preference", "correction_feedback"} else 0
            for word in words:
                if word in text:
                    score += 1
            if score:
                ranked.append((score, item))
        ranked.sort(key=lambda pair: (-pair[0], pair[1]["memory_id"]))
        selected = []
        for _, item in ranked[: self.max_selected]:
            record = await self.store.read_record(item["memory_id"])
            selected.append(record)
        await self.history.append_entry(
            EntryDraft(
                "memory_load",
                data={
                    "records": plain_data(selected),
                    "loaded_at": self.timing.now(),
                    "stale_after_days": 30,
                },
            )
        )
        return tuple(selected)

    async def settled(self, run_id):
        if self.mode == "off" or self.extractor is None or run_id in self.tasks:
            return
        snapshot = await self.history.snapshot()
        self.tasks[run_id] = asyncio.create_task(
            self.extract_snapshot(snapshot, run_id),
            name="pycode-memory-extract",
            context=contextvars.Context(),
        )

    async def extract_snapshot(self, snapshot, run_id, *, full=False):
        await self.history.append_entry(
            EntryDraft(
                "memory_extract",
                data={"status": "started", "source_leaf_id": snapshot.leaf_id},
                run_id=run_id,
            )
        )
        try:
            if full:
                candidates = await self.extractor.extract(snapshot, run_id, full=True)
            else:
                candidates = await self.extractor.extract(snapshot, run_id)
            await self.ingest(candidates, snapshot, model=self.extractor.model_id)
            await self.history.append_entry(
                EntryDraft(
                    "memory_extract",
                    data={"status": "completed", "source_leaf_id": snapshot.leaf_id},
                    run_id=run_id,
                )
            )
        except asyncio.CancelledError:
            await self.history.append_entry(
                EntryDraft(
                    "memory_extract",
                    data={
                        "status": "pending_retry",
                        "reason": "shutdown",
                        "source_leaf_id": snapshot.leaf_id,
                    },
                    run_id=run_id,
                )
            )
            raise
        except (ToolError, StorageError, ModelError) as error:
            await self.history.append_entry(
                EntryDraft(
                    "memory_extract",
                    data={
                        "status": "pending_retry",
                        "reason": error.code,
                        "source_leaf_id": snapshot.leaf_id,
                    },
                    run_id=run_id,
                )
            )

    async def remove(self, memory_id):
        record = await self.store.read_record(memory_id)
        await self.store.remove(memory_id, expected_hash=record.sha256)

    async def edit(self, memory_id, content, *, scope=None):
        previous = await self.store.read_record(memory_id)
        chosen_scope = scope or previous.scope
        if chosen_scope not in {"user", "project"}:
            raise StorageError("invalid_memory_scope", "Choose user or project memory")
        source_entry = await self.history.append_entry(
            EntryDraft(
                "memory_edit",
                data={"memory_id": memory_id},
                message=Message(uuid4().hex, "user", content),
            )
        )
        source_id = source_entry.id
        snapshot = await self.history.snapshot()
        await self.ingest(
            (
                {
                    "action": "update",
                    "target_id": memory_id,
                    "key": previous.key,
                    "category": previous.category,
                    "scope": chosen_scope,
                    "content": content,
                    "evidence": [{"entry_id": source_id, "quote": content}],
                },
            ),
            snapshot,
            model="user",
        )
        for candidate in await self.store.candidates():
            if candidate.status == "pending" and candidate.model == "user":
                for evidence in candidate.evidence:
                    if evidence["entry_id"] == source_id:
                        return await self.review(candidate.candidate_id, "approve")
        raise StorageError(
            "memory_edit_rejected", "Memory edit did not pass evidence and content validation"
        )

    async def clear(self):
        for item in await self.store.list_index():
            await self.remove(item["memory_id"])

    async def collect(self):
        for task in tuple(self.tasks.values()):
            await task

    async def aclose(self):
        tasks = tuple(self.tasks.values())
        for task in tasks:
            if not task.done():
                task.cancel()
        results = await asyncio.gather(*tasks, return_exceptions=True)
        self.tasks.clear()
        for result in results:
            if isinstance(result, BaseException) and not isinstance(result, asyncio.CancelledError):
                raise result
