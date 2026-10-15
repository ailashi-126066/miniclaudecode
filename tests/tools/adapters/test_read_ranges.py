import io

import pytest

from pycode.kernel.domain.errors import ToolError
from pycode.tools.adapters.read_range import read_range


def test_multibyte_byte_budget_can_continue_until_exact_original_is_recovered():
    raw = ("€😀a\n" * 19 + "结尾").encode()
    offset = 1
    byte_offset = 0
    pieces = []
    for _ in range(100):
        result = read_range(io.BytesIO(raw), "output", offset, 3, 11, byte_offset)
        assert len(result.text.encode()) <= 11
        assert "�" not in result.text
        pieces.append(result.text)
        if not result.has_more:
            break
        assert (result.next_offset, result.next_byte_offset) != (offset, byte_offset)
        offset, byte_offset = result.next_offset, result.next_byte_offset
    else:
        pytest.fail("Readback cursor did not finish")
    assert "".join(pieces).encode() == raw


@pytest.mark.parametrize("byte_offset", [1, 2, 5, 6])
def test_byte_continuation_rejects_mid_character_and_beyond_line(byte_offset):
    with pytest.raises(ToolError) as error:
        read_range(io.BytesIO("€x\nnext".encode()), "output", 1, 1, 20, byte_offset)
    assert error.value.code == "invalid_output_range"


def test_last_line_without_newline_and_empty_range_have_correct_cursor():
    final = read_range(io.BytesIO(b"one\ntwo"), "output", 2, 1, 10, 0)
    assert final.text == "two" and not final.has_more
    assert final.end_line == 2 and final.next_offset == 3
    empty = read_range(io.BytesIO(b"one\ntwo"), "output", 3, 1, 10, 0)
    assert empty.text == "" and empty.end_line == 2 and not empty.has_more
