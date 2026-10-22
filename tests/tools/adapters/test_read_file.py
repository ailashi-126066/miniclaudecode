"""Read windows use native zero-based offsets; complete giant reads use output objects."""

import pytest

from pycode.kernel.domain.truncation import MAX_BYTES
from pycode.tools.adapters.backend import PycodeBackend


async def test_read_existing_large_file_in_ranges_without_result_copy(tmp_path):
    path = tmp_path / "large.txt"
    path.write_text("".join(f"Line {i}\n" for i in range(1, 8001)), encoding="utf-8")
    result = await PycodeBackend(tmp_path).aread("/large.txt", 499, 100)
    assert result.error is None
    assert result.file_data["content"].splitlines()[0] == "Line 500"
    assert (
        "Line 599" in result.file_data["content"]
        and "Line 600\n" not in result.file_data["content"]
    )
    assert result.next_offset == 599
    assert len(result.file_data["content"].encode()) <= MAX_BYTES
    assert [file.name for file in tmp_path.iterdir()] == ["large.txt"]


async def test_read_giant_single_line_provides_finite_byte_continuation(tmp_path):
    from tests.tools.adapters.test_native_long_read import (
        test_native_giant_line_has_one_complete_object_and_bounded_continuation,
    )

    await test_native_giant_line_has_one_complete_object_and_bounded_continuation(tmp_path)


async def test_read_rejects_escape_and_missing_file(tmp_path):
    backend = PycodeBackend(tmp_path)
    assert (await backend.aread("/../outside")).error.startswith("outside_workspace")
    assert (await backend.aread("/missing")).error.startswith("file_not_found")


pytestmark = pytest.mark.usefixtures("disclosed_tools")
