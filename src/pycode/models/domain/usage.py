"""Nullable per-request totals preserve missing measurements and their coverage."""

from dataclasses import dataclass

from pycode.models.domain.requests import RequestRecord

TOKEN_FIELDS = (
    "input_tokens",
    "output_tokens",
    "cache_read_tokens",
    "cache_miss_tokens",
    "cache_write_tokens",
    "reasoning_tokens",
    "total_tokens",
)


@dataclass(frozen=True)
class UsageGroup:
    request_count: int
    settled_requests: int
    pending_requests: int
    tokens: dict[str, int | None]
    known_tokens: dict[str, int | None]
    reported: dict[str, int]


def aggregate_requests(records: tuple[RequestRecord, ...]) -> UsageGroup:
    settled = tuple(record for record in records if record.status != "started")
    tokens, known, reported = {}, {}, {}
    for field in TOKEN_FIELDS:
        values = []
        for record in settled:
            value = getattr(record.usage, field) if record.usage is not None else None
            if value is not None:
                values.append(value)
        reported[field] = len(values)
        known[field] = sum(values) if values else None
        tokens[field] = known[field] if len(values) == len(settled) else None
    return UsageGroup(
        len(records), len(settled), len(records) - len(settled), tokens, known, reported
    )


def origin_purpose(record: RequestRecord) -> str:
    if record.purpose != "retry":
        return record.purpose
    if record.usage is not None:
        return record.usage.raw.get("response_metadata", {}).get("pycode_origin_purpose", "unknown")
    return "unknown"
