"""Immutable tool operation identity and explicit durable settlement states."""

import hashlib

from pycode.core.tools import ToolSpec
from pycode.core.values import json_dumps, plain_data


def operation_spec_hash(spec: ToolSpec) -> str:
    payload = json_dumps(
        plain_data(spec), sort_keys=True, ensure_ascii=False, separators=(",", ":")
    )
    return hashlib.sha256(payload.encode()).hexdigest()
