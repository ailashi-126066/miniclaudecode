"""Reorganization uses unfinished source segments while completed groups stay immutable."""

from dataclasses import dataclass, replace

from pycode.core.errors import ToolError
from pycode.core.memory_schema import resolve_memory_key, validate_memory_value
from pycode.core.values import freeze_value
from pycode.services.memory_commit import observed_targets, remaining_proposals


def user_sources(texts):
    return [
        {"session_id": text.session_id, "entry_id": text.entry_id, "quote": text.text}
        for text in texts
        if text.role == "user" and text.text.strip() and len(text.text) <= 16000
    ]


def quotes_for(changes, delete_sources):
    quotes = []
    for key, value in changes.items():
        sources = delete_sources.get(key, ()) if value is None else value["content"]["sources"]
        quotes.extend(sources)
    return quotes


def merge_ranges(intervals):
    merged = []
    for start, end in sorted(intervals):
        if merged and start <= merged[-1][1]:
            merged[-1] = (merged[-1][0], max(end, merged[-1][1]))
        else:
            merged.append((start, end))
    return merged


def source_segments(text, completed, pending):
    identity = (text.session_id, text.entry_id)
    kept = {s["quote"] for s in pending if (s.get("session_id"), s.get("entry_id")) == identity}
    intervals = []
    quotes = {
        s["quote"]
        for s in completed
        if (s.get("session_id"), s.get("entry_id")) == identity and s.get("quote")
    }
    for quote in quotes:
        occurrences = []
        index = text.text.find(quote)
        while index >= 0:
            right = index + len(quote)
            if occurrences and index <= occurrences[-1][1]:
                occurrences[-1] = (occurrences[-1][0], right)
            else:
                occurrences.append((index, right))
            index = text.text.find(quote, index + 1)
        intervals.extend(occurrences)
    merged = merge_ranges(intervals)
    segments, start = [], 0
    for left, right in merged:
        if left > start:
            segments.append((start, left))
        start = right
    if start < len(text.text):
        segments.append((start, len(text.text)))
    # An unfinished quote may overlap a completed quote; retain its exact original span.
    for quote in kept:
        left = text.text.find(quote)
        if left >= 0:
            segments.append((left, left + len(quote)))
    return tuple(
        replace(text, text=text.text[left:right], start=text.start + left)
        for left, right in merge_ranges(segments)
        if text.text[left:right].strip()
    )


@dataclass(frozen=True)
class MemoryRetryPlan:
    texts: tuple
    proposals: dict
    completed_proposals: dict
    protected_keys: frozenset
    latest_targets: dict

    def __post_init__(self):
        for name in ("proposals", "completed_proposals", "latest_targets"):
            object.__setattr__(self, name, freeze_value(getattr(self, name)))

    def validate(self, key, value, previous, *, delete_sources=()):
        if key in self.protected_keys:
            raise ToolError(
                "memory_completed_group_protected", "Reorganization cannot change a saved group"
            )
        sources = delete_sources if value is None else value["content"]["sources"]
        inherited = previous["value"]["content"]["sources"] if previous else ()
        active = False
        for source in sources:
            allowed = any(
                text.session_id == source.get("session_id")
                and text.entry_id == source.get("entry_id")
                and source.get("quote", "").strip()
                and source["quote"] in text.text
                for text in self.texts
            )
            if allowed:
                active = True
            elif value is None or source not in inherited:
                raise ToolError(
                    "memory_retry_source_invalid", "Reorganization cited a completed source"
                )
        if not active:
            raise ToolError(
                "memory_retry_source_invalid", "Reorganization needs unfinished source evidence"
            )


def retry_plan(evidence, state, proposals, batch_id):
    proposals = {
        key: validate_memory_value(value) if value is not None else None
        for key, value in proposals.items()
    }
    deletes = {
        key: user_sources(evidence.texts) for key, value in proposals.items() if value is None
    }
    pending, _, _ = remaining_proposals(proposals, deletes, state["group_receipts"], batch_id)
    completed = {key: value for key, value in proposals.items() if key not in pending}
    protected = set(completed)
    for receipt in state["group_receipts"].values():
        if receipt["batch_id"] == batch_id:
            for key in receipt["canonical_keys"]:
                protected.add(resolve_memory_key(state["records"], key))
    done_quotes, pending_quotes = quotes_for(completed, deletes), quotes_for(pending, deletes)
    texts = []
    for text in evidence.texts:
        texts.extend(source_segments(text, done_quotes, pending_quotes))
    targets = observed_targets(pending, state["records"])
    latest = {key: state["records"].get(target["key"]) for key, target in targets.items()}
    return MemoryRetryPlan(tuple(texts), pending, completed, frozenset(protected), latest)
