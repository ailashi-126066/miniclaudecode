"""Bounded binary line reads with exact byte continuation at UTF-8 boundaries."""

import codecs
from typing import BinaryIO

from pycode.core.errors import ToolError
from pycode.core.outputs import OutputSlice


def decode_prefix(raw: bytes, max_bytes: int) -> tuple[str, int]:
    decoder = codecs.getincrementaldecoder("utf-8")("replace")
    pieces = []
    size = 0
    consumed = 0
    for index, byte in enumerate(raw):
        text = decoder.decode(bytes((byte,)), final=index == len(raw) - 1)
        if size + len(text.encode()) > max_bytes:
            break
        if text:
            pieces.append(text)
            size += len(text.encode())
            consumed = index + 1
    return "".join(pieces), consumed


def skip_line_bytes(stream: BinaryIO, byte_offset: int) -> None:
    decoder = codecs.getincrementaldecoder("utf-8")("replace")
    skipped = 0
    while skipped < byte_offset:
        data = stream.read(min(byte_offset - skipped, 64 * 1024))
        if not data or b"\n" in data:
            raise ToolError("invalid_output_range", "Byte offset is outside the requested line")
        decoder.decode(data)
        skipped += len(data)
    if decoder.getstate()[0]:
        raise ToolError("invalid_output_range", "Byte offset splits a UTF-8 character")


def read_range(
    stream: BinaryIO,
    output_id: str,
    offset: int,
    limit: int,
    max_bytes: int,
    byte_offset: int,
    absolute_byte_offset: int | None = None,
) -> OutputSlice:
    line = 1
    if absolute_byte_offset is not None:
        size = stream.seek(0, 2)
        if absolute_byte_offset > size:
            raise ToolError("invalid_output_range", "Absolute cursor exceeds the output")
        stream.seek(absolute_byte_offset)
        first = stream.read(1)
        if first and first[0] & 0xC0 == 0x80:
            raise ToolError("invalid_output_range", "Absolute cursor splits a UTF-8 character")
        stream.seek(absolute_byte_offset)
        line = offset
    while line < offset:
        chunk = stream.readline(64 * 1024)
        if not chunk:
            return OutputSlice(output_id, "", offset, offset - 1, False, offset, 0)
        if chunk.endswith(b"\n"):
            line += 1
    if absolute_byte_offset is None:
        skip_line_bytes(stream, byte_offset)
    pieces = []
    visible = 0
    returned_lines = 0
    cursor = byte_offset
    end_line = offset - 1
    while returned_lines < limit and visible < max_bytes:
        raw = stream.readline(max_bytes - visible + 4)
        if not raw:
            return OutputSlice(output_id, "".join(pieces), offset, end_line, False, line, 0)
        text, consumed = decode_prefix(raw, max_bytes - visible)
        pieces.append(text)
        visible += len(text.encode())
        end_line = line
        cursor += consumed
        returned_lines += 1
        if consumed < len(raw) or not raw.endswith(b"\n"):
            more = consumed < len(raw) or bool(stream.read(1))
            return OutputSlice(
                output_id,
                "".join(pieces),
                offset,
                end_line,
                more,
                line if more else line + 1,
                cursor if more else 0,
            )
        line += 1
        cursor = 0
    more = bool(stream.read(1))
    return OutputSlice(output_id, "".join(pieces), offset, end_line, more, line, 0)
