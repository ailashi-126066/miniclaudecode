"""Canonical JSON SHA-256 identity shared by records, receipts and configuration checks."""

import hashlib

from pycode.core.values import json_dumps


def data_hash(value) -> str:
    encoded = json_dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":"))
    return hashlib.sha256(encoded.encode()).hexdigest()
