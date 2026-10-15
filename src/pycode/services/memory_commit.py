"""Automatic group validation and publication through the sole job-owned JSON update view."""

from pycode.core.errors import ModelError, StorageError, ToolError
from pycode.core.hashing import data_hash
from pycode.core.memory import content_hash
from pycode.core.memory_evidence import MemoryCommitReport
from pycode.core.memory_schema import resolve_memory_key, validate_memory_value
from pycode.core.values import plain_data


def claim_identity(value):
    content = {
        k: v for k, v in value["content"].items() if k not in {"title", "summary", "sources"}
    }
    if value["kind"] == "ProceduralMemory":
        content.pop("origin", None)
    return data_hash({"kind": value["kind"], "claim": content})


def deduplicate(changes, previous):
    by_claim = {claim_identity(record["value"]): key for key, record in previous.items()}
    result, targets = {}, {}
    for key, value in changes.items():
        original_key = key
        if value is not None and key not in previous:
            identity = claim_identity(value)
            key = by_claim.get(identity, key)
            if key in result or key in previous:
                old = result.get(key) or previous[key]["value"]
                merged = plain_data(old)
                for source in value["content"]["sources"]:
                    if source not in merged["content"]["sources"]:
                        merged["content"]["sources"].append(source)
                value = merged
            by_claim[identity] = key
        result[key] = value
        targets[original_key] = key
    return result, targets


def associated_groups(changes, delete_sources):
    groups = []
    for key, value in changes.items():
        sources = delete_sources.get(key, ()) if value is None else value["content"]["sources"]
        identities = {
            (
                s.get("session_id"),
                s.get("entry_id"),
                s.get("sha256") or content_hash(s.get("quote", "")),
            )
            for s in sources
        }
        intersecting = [group for group in groups if identities & group[1]]
        keys = {key}
        for group in intersecting:
            keys.update(group[0])
            identities.update(group[1])
            groups.remove(group)
        groups.append((keys, identities))
    return tuple(tuple(sorted(keys)) for keys, _ in groups)


def group_identity(batch_id, changes):
    digest = data_hash(changes)
    return data_hash({"batch_id": batch_id, "proposal_sha256": digest}), digest


def remaining_proposals(changes, delete_sources, receipts, batch_id):
    remaining = dict(changes)
    groups, completed = [], []
    for keys in associated_groups(changes, delete_sources):
        values = {key: changes[key] for key in keys}
        identity, digest = group_identity(batch_id, values)
        prior = receipts.get(identity) if batch_id is not None else None
        if prior is not None:
            if prior["batch_id"] != batch_id or prior["proposal_sha256"] != digest:
                raise StorageError("memory_group_conflict", "Group receipt has another proposal")
            completed.extend(prior["canonical_keys"])
            for key in keys:
                remaining.pop(key)
        else:
            groups.append((identity, digest, keys))
    return remaining, groups, completed


def observed_targets(changes, previous):
    _, mapping = deduplicate(changes, previous)
    return {
        key: {"key": target, "record_sha256": data_hash(previous.get(target))}
        for key, target in mapping.items()
    }


class MemoryCommitService:
    def __init__(self, history, policy, permit, review):
        self.history = history
        self.policy = policy
        self.permit = permit
        self.review = review

    async def commit(
        self,
        view,
        namespace,
        *,
        delete_sources=None,
        batch_id=None,
        progress=None,
        expected_targets=None,
        retry_plan=None,
        tool_receipt=None,
        stage_rejections=None,
    ):
        prepared = view.prepare()
        if namespace not in self.policy.scopes or namespace not in prepared.previous:
            raise ToolError("memory_scope_denied", "Memory scope is outside this task")
        previous = plain_data(prepared.previous[namespace])
        raw_changes = plain_data(prepared.changes[namespace])
        delete_sources = {
            resolve_memory_key(previous, key): sources
            for key, sources in (delete_sources or {}).items()
        }
        pending, raw_groups, completed = remaining_proposals(
            raw_changes,
            delete_sources,
            plain_data(prepared.group_receipts.get(namespace, {})),
            batch_id,
        )
        changes, mapping = deduplicate(pending, previous)
        stage_rejections = {
            mapping[key]: reason
            for key, reason in (stage_rejections or {}).items()
            if key in pending
        }
        for raw_key in pending:
            observed = (expected_targets or {}).get(raw_key)
            if observed is not None and (
                mapping[raw_key] != observed["key"]
                or data_hash(previous.get(observed["key"])) != observed["record_sha256"]
            ):
                raise StorageError("memory_target_conflict", "An unfinished memory target changed")
        groups = associated_groups(changes, delete_sources)
        if len(groups) > 16:
            raise ToolError("memory_review_budget", "Memory change groups exceed the review budget")
        accepted = {}
        rejected = []
        for keys in groups:
            group_id = data_hash({"namespace": namespace, "keys": keys})
            try:
                checked = []
                proposals = []
                for key in keys:
                    if key in (stage_rejections or {}):
                        raise ToolError(stage_rejections[key], "Behavior rule preparation failed")
                    if changes[key] is not None:
                        changes[key] = validate_memory_value(changes[key])
                    if retry_plan is not None:
                        retry_plan.validate(
                            key,
                            changes[key],
                            previous.get(key),
                            delete_sources=delete_sources.get(key, ()),
                        )
                    checked.extend(
                        self.policy.validate(
                            namespace,
                            key,
                            changes[key],
                            previous.get(key),
                            delete_sources=delete_sources.get(key, ()),
                        )
                    )
                    proposals.append(
                        {"key": key, "value": changes[key], "previous": previous.get(key)}
                    )
                decision = await self.review(
                    self.policy.scopes[namespace], proposals, tuple(checked)
                )
                if not decision.supported or not decision.durable or not decision.scope_valid:
                    raise ToolError(
                        "memory_claim_unsupported",
                        "Memory change is unsupported, temporary or outside its scope",
                    )
                if any(changes[key] is None for key in keys) and not decision.deletion_requested:
                    raise ToolError(
                        "memory_delete_unsupported",
                        "Evidence does not request deletion of this target",
                    )
                if (
                    any(
                        changes[key] is not None
                        and changes[key]["kind"] == "EpisodicMemory"
                        and changes[key]["content"]["verification"] == "verified"
                        for key in keys
                    )
                    and not decision.outcome_verified
                ):
                    raise ToolError(
                        "memory_outcome_unverified", "Evidence does not verify the claimed outcome"
                    )
                accepted.update({key: changes[key] for key in keys})
            except (ToolError, ModelError) as error:
                rejected.append({"group_id": group_id, "keys": list(keys), "reason": error.code})
        self.policy.evidence.check_current(await self.history.snapshot())
        if not rejected and batch_id is not None:
            evidence = self.policy.evidence
            source_map = {text.entry_id: text for text in evidence.texts}
            ids = (progress or {}).get("entry_ids", ())
            if (
                not ids
                or (progress or {}).get("session_id") != evidence.session_id
                or (progress or {}).get("prefix_hash") != evidence.prefix_hash
                or any(entry_id not in source_map for entry_id in ids)
            ):
                raise ToolError(
                    "invalid_memory_progress",
                    "Memory progress is outside the captured source range",
                )
            eligible = evidence.eligible_ids
            if (
                ids[0] not in eligible
                or tuple(ids)
                != eligible[eligible.index(ids[0]) : eligible.index(ids[0]) + len(ids)]
            ):
                raise ToolError(
                    "invalid_memory_progress",
                    "Memory source progress must cover a contiguous message range",
                )
            for boundary in ("start", "end"):
                cursor = progress.get(boundary, {})
                datum = source_map.get(cursor.get("entry_id"))
                offset = cursor.get("offset")
                if (
                    datum is None
                    or type(offset) is not int
                    or not datum.start <= offset <= datum.start + len(datum.text)
                ):
                    raise ToolError(
                        "invalid_memory_progress",
                        "Memory source cursor is outside its captured text",
                    )
        receipts = {}
        if batch_id is not None:
            for identity, digest, keys in raw_groups:
                canonical = sorted({mapping[key] for key in keys})
                if all(key in accepted for key in canonical):
                    receipts[identity] = {
                        "batch_id": batch_id,
                        "proposal_sha256": digest,
                        "canonical_keys": canonical,
                    }
        self.permit.issue(namespace, accepted, previous)
        await view.commit(
            namespace,
            accepted_changes=accepted,
            batch_id=batch_id if not rejected else None,
            progress=progress if not rejected else None,
            group_receipts=receipts,
            tool_receipt=tool_receipt if not rejected else None,
        )
        accepted_ids = tuple(dict.fromkeys([*completed, *accepted]))
        return MemoryCommitReport(
            accepted_ids,
            tuple(rejected),
            not rejected,
            reorganize_reason="memory_behavior_stage_failed" if stage_rejections else None,
        )
