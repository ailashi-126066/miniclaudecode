"""Atomic catalog cache containing model identities and capabilities, never keys."""

import asyncio
import json
from pathlib import Path

from pydantic import TypeAdapter

from pycode.adapters.storage.path_io import atomic_write
from pycode.core.errors import StorageError
from pycode.core.models import CatalogSnapshot
from pycode.core.values import json_dumps, plain_data


class FileCatalogStore:
    def __init__(self, path: Path) -> None:
        self._path = path
        self._lock = asyncio.Lock()

    async def run_io(self, operation):
        async with self._lock:
            owned = asyncio.create_task(asyncio.to_thread(operation))
            try:
                return await asyncio.shield(owned)
            except asyncio.CancelledError:
                await owned
                raise
            except (OSError, ValueError, TypeError, KeyError) as error:
                raise StorageError(
                    "invalid_catalog_cache", "Cannot read or publish model catalog"
                ) from error

    async def load(self) -> CatalogSnapshot | None:
        def read():
            if not self._path.exists():
                return None
            payload = json.loads(self._path.read_bytes())
            if payload["schema_version"] != 1:
                raise ValueError("Unsupported catalog schema")
            payload.pop("schema_version")
            return TypeAdapter(CatalogSnapshot).validate_json(json_dumps(payload), strict=True)

        return await self.run_io(read)

    async def save(self, snapshot: CatalogSnapshot) -> None:
        def write():
            payload = {"schema_version": 1, **plain_data(snapshot)}
            encoded = json_dumps(payload, ensure_ascii=False, sort_keys=True).encode("utf-8")
            atomic_write(self._path, encoded)

        await self.run_io(write)
