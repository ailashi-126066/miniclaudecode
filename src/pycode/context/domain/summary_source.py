# Source: https://github.com/earendil-works/pi/blob/42a3497d03ad17e308a2299fa824727894f2c0ec/packages/coding-agent/src/core/compaction/compaction.ts
# Source: MewCode local mewcode/context/summary_cache.py; SHA-256:
# e291f27c5264240e6a535b4ec997e0958cec665f5be1e36f0f9f5f7b08621db0
# Adaptation: canonical projected entry IDs, config/checkpoint hashes and paired native cuts.
"""Capture and validate a fixed summary prefix independently of appended tail messages."""

from pycode.context.domain.compaction import find_cut_point
from pycode.context.domain.summaries import SummarySource
from pycode.kernel import (
    ModelSelection,
    ProjectedContext,
    SessionSnapshot,
    SessionTree,
    clone_value,
    data_hash,
    plain_data,
    valid_kept_boundary,
)


def capture_source(
    snapshot: SessionSnapshot,
    projection: ProjectedContext,
    *,
    job_id: str,
    selection: ModelSelection,
    config_hash: str,
    checkpoint: dict,
    keep_recent_tokens: int,
) -> SummarySource | None:
    path = SessionTree(snapshot.index).path(snapshot.leaf_id)
    by_id = {entry.id: entry for entry in path}
    entries = tuple(by_id[item.entry_id] for item in projection.entries)
    if not entries:
        return None
    cut = find_cut_point(entries, projection, keep_recent_tokens)
    index = cut.first_kept_index
    while index > 0 and not valid_kept_boundary(path, entries[index].id):
        index -= 1
    if index <= 0:
        return None
    included = projection.entries[:index]
    visible = tuple(item for item in included if any(m.role != "system" for m in item.messages))
    if not any(by_id[item.entry_id].type != "compaction" for item in visible):
        return None
    flattened = []
    for item in visible:
        for message in item.messages:
            if message.role != "system":
                flattened.append(message)
    messages = tuple(flattened)
    previous = next((entry.id for entry in reversed(path) if entry.type == "compaction"), None)
    turn_prefix_start = None
    if cut.split_turn and cut.turn_start_index >= 0 and cut.turn_start_index < index:
        prefix_id = entries[cut.turn_start_index].id
        turn_prefix_start = sum(
            len(item.messages)
            for item in visible
            if entries.index(by_id[item.entry_id]) < cut.turn_start_index
        )
        if prefix_id not in {item.entry_id for item in visible}:
            turn_prefix_start = None
    return SummarySource(
        job_id,
        snapshot.header.session_id,
        snapshot.leaf_id,
        tuple(item.entry_id for item in visible),
        entries[index].id,
        previous,
        data_hash([plain_data(item) for item in visible]),
        config_hash,
        clone_value(checkpoint),
        clone_value(messages),
        turn_prefix_start,
        clone_value(selection),
    )


def valid_source(
    source: SummarySource, snapshot: SessionSnapshot, projection: ProjectedContext, config_hash: str
) -> bool:
    if source.session_id != snapshot.header.session_id or source.config_hash != config_hash:
        return False
    path = SessionTree(snapshot.index).path(snapshot.leaf_id)
    previous = next((entry.id for entry in reversed(path) if entry.type == "compaction"), None)
    if previous != source.previous_compaction_id or source.first_kept_entry_id not in {
        e.id for e in path
    }:
        return False
    visible = tuple(
        item for item in projection.entries if any(m.role != "system" for m in item.messages)
    )
    prefix = visible[: len(source.source_entry_ids)]
    if tuple(item.entry_id for item in prefix) != source.source_entry_ids:
        return False
    return data_hash([plain_data(item) for item in prefix]) == source.prefix_hash
