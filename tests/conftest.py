import shutil

import pytest

RG_SKIP_REASON = "ripgrep (rg) is not on PATH; install it (e.g. `brew install ripgrep`) to run"


def pytest_configure(config):
    config.addinivalue_line(
        "markers", "requires_rg: test runs the real ripgrep binary and is skipped without rg"
    )


def pytest_collection_modifyitems(config, items):
    if shutil.which("rg") is not None:
        return
    skip = pytest.mark.skip(reason=RG_SKIP_REASON)
    for item in items:
        if item.get_closest_marker("requires_rg") is not None:
            item.add_marker(skip)
