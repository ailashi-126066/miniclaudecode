"""Bounded complete-output windows retain trusted UTF-8 byte and character positions."""

from pycode.core.errors import StorageError, ToolError
from pycode.core.memory_schema import MemoryOutputPosition
from pycode.core.truncation import MAX_BYTES, MAX_LINES
from pycode.core.values import json_dumps


def advance_output(position, text):
    value = position.model_dump(mode="json")
    lines = text.count("\n")
    value["character_offset"] += len(text)
    value["absolute_byte_offset"] += len(text.encode("utf-8"))
    value["line_offset"] += lines
    if lines:
        value["byte_offset"] = len(text.rsplit("\n", 1)[1].encode("utf-8"))
    else:
        value["byte_offset"] += len(text.encode("utf-8"))
    return MemoryOutputPosition.model_validate_json(json_dumps(value))


async def output_window(reader, output_id, start, count, *, position=None):
    current = (
        MemoryOutputPosition(output_id=output_id)
        if position is None
        else MemoryOutputPosition.model_validate_json(json_dumps(position))
    )
    if current.output_id != output_id or current.character_offset > start:
        raise StorageError(
            "memory_output_cursor_invalid", "Output cursor has another source or range"
        )
    pieces, size, beginning = [], 0, None
    for _ in range(128):
        part = await reader.read(
            output_id,
            current.line_offset,
            MAX_LINES,
            MAX_BYTES,
            current.byte_offset,
            absolute_byte_offset=current.absolute_byte_offset,
        )
        next_position = advance_output(current, part.text)
        if next_position.character_offset > start or not part.has_more:
            cut = max(0, start - current.character_offset)
            if beginning is None:
                beginning = advance_output(current, part.text[:cut])
            value = part.text[cut:]
            remaining = count + 1 - size
            pieces.append(value[:remaining])
            size += min(len(value), remaining)
        if size > count or not part.has_more:
            if next_position.character_offset < start:
                raise StorageError("memory_source_changed", "Cursor exceeds the complete output")
            text = "".join(pieces)
            return text[:count], not part.has_more and len(text) <= count, beginning
        if next_position.absolute_byte_offset <= current.absolute_byte_offset:
            raise StorageError("memory_source_invalid", "Complete output readback made no progress")
        current = next_position
    raise ToolError("memory_source_budget", "Output window exceeds its bounded read budget")
