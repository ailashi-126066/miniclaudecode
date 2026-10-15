# Source: https://github.com/earendil-works/pi/blob/42a3497d03ad17e308a2299fa824727894f2c0ec/packages/coding-agent/src/core/compaction/compaction.ts
# Adaptation: validate explicit retained entry boundaries before publishing compaction entries.
"""A retained history range must not split a call from its completed result."""

from pycode.kernel import SessionEntry


def valid_kept_boundary(path: tuple[SessionEntry, ...], first_kept: str | None) -> bool:
    boundary = (
        len(path)
        if first_kept is None
        else next((index for index, entry in enumerate(path) if entry.id == first_kept), -1)
    )
    if boundary < 0:
        return False
    issued = {}
    for index, entry in enumerate(path):
        message = entry.message
        if message is None:
            continue
        for call in message.tool_calls:
            issued[call.id] = index
        if message.tool_result is not None:
            start = issued.pop(message.tool_result.call_id, None)
            if start is not None and start < boundary <= index:
                return False
    return not any(index < boundary for index in issued.values())
