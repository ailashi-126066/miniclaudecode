"""Finish existing legacy transactions before migration; no legacy creation or recall API."""

import os
import re

from pycode.adapters.storage.memory_migration import read_json, safe_relative
from pycode.adapters.storage.path_io import atomic_write
from pycode.core.errors import StorageError
from pycode.core.memory import content_hash
from pycode.core.values import json_dumps


class LegacyMemoryRecovery:
    def recover_transactions(self, root):
        if (root / "memory.json").exists() or (root / "memory.json").is_symlink():
            raise StorageError(
                "memory_store_migrated", "Legacy recovery cannot modify active JSON memory"
            )
        directory = safe_relative(root, "transactions")
        if not directory.exists():
            return
        for pending in sorted(directory.iterdir()):
            if pending.is_symlink() or not re.fullmatch(r"[a-f0-9]{32}", pending.name):
                raise StorageError(
                    "memory_transaction_invalid", "Memory transaction path is invalid"
                )
            manifest = safe_relative(root, f"transactions/{pending.name}/manifest.json")
            if not manifest.exists():
                continue
            transaction = read_json(manifest)
            if transaction.get("status") == "complete":
                continue
            identifier = transaction.get("memory_id", "")
            if transaction.get("status") != "prepared" or not re.fullmatch(
                r"[a-f0-9]{32}", identifier
            ):
                raise StorageError(
                    "memory_transaction_invalid", "Memory transaction identity is invalid"
                )
            if transaction.get("action") == "remove":
                self.finish_removal(root, pending, transaction)
                continue
            staged_content = safe_relative(root, f"transactions/{pending.name}/content.md")
            staged_record = safe_relative(root, f"transactions/{pending.name}/record.json")
            content_path = safe_relative(root, identifier + ".md")
            record_path = safe_relative(root, identifier + ".json")
            body = (staged_content if staged_content.exists() else content_path).read_bytes()
            metadata = read_json(staged_record if staged_record.exists() else record_path)
            self.verify(body, metadata, transaction)
            if staged_content.exists():
                os.replace(staged_content, content_path)
            if staged_record.exists():
                os.replace(staged_record, record_path)
            transaction["status"] = "complete"
            atomic_write(manifest, json_dumps(transaction, sort_keys=True).encode())

    def verify(self, body, metadata, transaction):
        if (
            content_hash(body.decode("utf-8")) != transaction["sha256"]
            or metadata.get("sha256") != transaction["sha256"]
            or metadata.get("memory_id") != transaction["memory_id"]
        ):
            raise StorageError(
                "memory_transaction_corrupt", "Legacy transaction checksum or identity differs"
            )

    def finish_removal(self, root, pending, transaction):
        identifier = transaction["memory_id"]
        destination = safe_relative(root, "removed/" + identifier)
        destination.mkdir(parents=True, exist_ok=True)
        content = safe_relative(root, identifier + ".md")
        record = safe_relative(root, identifier + ".json")
        archived_content = safe_relative(root, f"removed/{identifier}/content.md")
        archived_record = safe_relative(root, f"removed/{identifier}/record.json")
        body = (content if content.exists() else archived_content).read_bytes()
        metadata = read_json(record if record.exists() else archived_record)
        self.verify(body, metadata, transaction)
        if content.exists():
            os.replace(content, archived_content)
        if record.exists():
            os.replace(record, archived_record)
        transaction["status"] = "complete"
        atomic_write(pending / "manifest.json", json_dumps(transaction, sort_keys=True).encode())
