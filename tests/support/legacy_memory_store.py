"""Preserved old-format writer for migration fixtures only; never imported by runtime code."""

import json
import os
import re
import time
from pathlib import Path
from uuid import uuid4

from pycode.adapters.storage.file_lease import FileLease
from pycode.adapters.storage.path_io import atomic_write, owned_io
from pycode.core.errors import StorageError
from pycode.core.memory import content_hash
from pycode.core.values import json_dumps, plain_data
from tests.support.legacy_memory_types import MemoryCandidate, MemoryRecord


class FileMemoryStore:
    def __init__(self, user_root: Path, project_root: Path):
        self.roots = {"user": user_root.resolve(), "project": project_root.resolve()}

    @staticmethod
    def ensure_legacy(root):
        if (root / "memory.json").exists() or (root / "memory.json").is_symlink():
            raise StorageError(
                "memory_store_migrated", "Memory uses the JSON Store; legacy access is retired"
            )

    def recover_transactions(self, root):
        self.ensure_legacy(root)
        directory = root / "transactions"
        if not directory.exists():
            return
        for pending in sorted(directory.iterdir()):
            if pending.is_symlink() or not re.fullmatch(r"[a-f0-9]{32}", pending.name):
                raise StorageError(
                    "memory_transaction_invalid", "Memory transaction path is invalid"
                )
            manifest = pending / "manifest.json"
            if not manifest.exists():
                continue
            transaction = json.loads(manifest.read_bytes())
            if transaction["status"] == "complete":
                continue
            identifier = transaction["memory_id"]
            self.check_id(identifier)
            if transaction.get("action") == "remove":
                self.finish_removal(root, pending, transaction)
                continue
            staged_content = pending / "content.md"
            staged_record = pending / "record.json"
            content_path = root / (identifier + ".md")
            record_path = root / (identifier + ".json")
            body = (
                staged_content.read_bytes()
                if staged_content.exists()
                else content_path.read_bytes()
            )
            if content_hash(body.decode("utf-8")) != transaction["sha256"]:
                raise StorageError("memory_transaction_corrupt", "Staged memory checksum failed")
            metadata = json.loads(
                staged_record.read_bytes() if staged_record.exists() else record_path.read_bytes()
            )
            if metadata["sha256"] != transaction["sha256"] or metadata["memory_id"] != identifier:
                raise StorageError("memory_transaction_corrupt", "Staged memory identity changed")
            if staged_content.exists():
                os.replace(staged_content, content_path)
            if staged_record.exists():
                os.replace(staged_record, record_path)
            transaction["status"] = "complete"
            atomic_write(manifest, json_dumps(transaction, sort_keys=True).encode())
            self.change(
                root,
                {
                    "action": "transaction_complete",
                    "memory_id": identifier,
                    "sha256": transaction["sha256"],
                },
            )
            self.write_index(root)

    def finish_removal(self, root, pending, transaction):
        identifier = transaction["memory_id"]
        destination = root / "removed" / identifier
        destination.mkdir(parents=True, exist_ok=True)
        content = root / (identifier + ".md")
        record = root / (identifier + ".json")
        archived_content = destination / "content.md"
        archived_record = destination / "record.json"
        body = content.read_bytes() if content.exists() else archived_content.read_bytes()
        metadata = json.loads(
            record.read_bytes() if record.exists() else archived_record.read_bytes()
        )
        if content_hash(body.decode("utf-8")) != transaction["sha256"]:
            raise StorageError("memory_transaction_corrupt", "Removed memory checksum failed")
        if metadata["memory_id"] != identifier or metadata["sha256"] != transaction["sha256"]:
            raise StorageError("memory_transaction_corrupt", "Removed memory identity changed")
        if content.exists():
            os.replace(content, archived_content)
        if record.exists():
            os.replace(record, archived_record)
        transaction["status"] = "complete"
        atomic_write(pending / "manifest.json", json_dumps(transaction, sort_keys=True).encode())
        self.change(
            root, {"action": "remove", "memory_id": identifier, "sha256": transaction["sha256"]}
        )
        self.write_index(root)

    def check_id(self, identifier):
        if not re.fullmatch(r"[a-f0-9]{32}", identifier):
            raise StorageError("invalid_memory_id", "Memory identity must be UUID hex")

    def list_sync(self):
        records = []
        for scope, root in self.roots.items():
            self.ensure_legacy(root)
            if not root.exists():
                continue
            for path in sorted(root.glob("*.json")):
                if not re.fullmatch(r"[a-f0-9]{32}", path.stem):
                    continue
                metadata = json.loads(path.read_bytes())
                content_path = root / (path.stem + ".md")
                content = content_path.read_bytes().decode("utf-8")
                if metadata["scope"] != scope or content_hash(content) != metadata["sha256"]:
                    raise StorageError(
                        "memory_corrupt", "Memory scope or content checksum is invalid"
                    )
                metadata["content"] = content
                metadata.pop("summary", None)
                metadata["evidence"] = tuple(metadata.get("evidence", ()))
                records.append(MemoryRecord(**metadata))
        return tuple(records)

    async def list_records(self):
        return await owned_io(self.read_consistent, self.list_sync)

    def read_consistent(self, action):
        leases = []
        try:
            for root in sorted(self.roots.values(), key=str):
                if not root.exists():
                    continue
                lease = FileLease(root / "memory.lock")
                for attempt in range(50):
                    try:
                        lease.acquire()
                        break
                    except StorageError as error:
                        if error.code != "session_busy" or attempt == 49:
                            raise
                        time.sleep(0.01)
                leases.append(lease)
                self.ensure_legacy(root)
                self.recover_transactions(root)
            return action()
        finally:
            for lease in reversed(leases):
                lease.close()

    async def list_index(self):
        def read():
            entries = []
            for scope, root in self.roots.items():
                if not root.exists():
                    continue
                for path in sorted(root.glob("*.json")):
                    if not re.fullmatch(r"[a-f0-9]{32}", path.stem):
                        continue
                    data = json.loads(path.read_bytes())
                    entries.append(
                        {
                            "memory_id": data["memory_id"],
                            "key": data["key"],
                            "scope": scope,
                            "category": data["category"],
                            "updated_at": data["updated_at"],
                            "sha256": data["sha256"],
                            "summary": data.get("summary", ""),
                        }
                    )
            return tuple(entries)

        return await owned_io(self.read_consistent, read)

    async def read_record(self, memory_id):
        def read():
            root, metadata = self.locate(memory_id)
            content = (root / (memory_id + ".md")).read_bytes().decode("utf-8")
            if content_hash(content) != metadata["sha256"]:
                raise StorageError("memory_corrupt", "Memory content checksum is invalid")
            metadata.pop("summary", None)
            metadata["content"] = content
            metadata["evidence"] = tuple(metadata["evidence"])
            return MemoryRecord(**metadata)

        return await owned_io(self.read_consistent, read)

    async def candidates(self):
        def read():
            found = []
            for root in self.roots.values():
                directory = root / "candidates"
                if not directory.exists():
                    continue
                for path in sorted(directory.glob("*.json")):
                    data = json.loads(path.read_bytes())
                    data["evidence"] = tuple(data["evidence"])
                    found.append(MemoryCandidate(**data))
            return tuple(found)

        return await owned_io(self.read_consistent, read)

    async def save_candidate(self, candidate):
        self.check_id(candidate.candidate_id)
        scope = candidate.scope if candidate.scope in self.roots else "user"
        path = self.roots[scope] / "candidates" / (candidate.candidate_id + ".json")
        await owned_io(
            self.read_consistent,
            lambda: atomic_write(
                path,
                json_dumps(plain_data(candidate), ensure_ascii=False, sort_keys=True).encode(
                    "utf-8"
                ),
            ),
        )

    def locate(self, memory_id):
        self.check_id(memory_id)
        for root in self.roots.values():
            self.ensure_legacy(root)
            path = root / (memory_id + ".json")
            if path.exists():
                return root, json.loads(path.read_bytes())
        raise StorageError("memory_not_found", "Memory is not in user or project storage")

    async def write(self, record, *, expected_hash=None):
        self.check_id(record.memory_id)
        root = self.roots[record.scope]

        def write():
            lease = FileLease(root / "memory.lock")
            lease.acquire()
            try:
                self.recover_transactions(root)
                metadata = root / (record.memory_id + ".json")
                if metadata.exists():
                    previous = json.loads(metadata.read_bytes())
                    if expected_hash != previous["sha256"]:
                        raise StorageError(
                            "memory_version_conflict", "Memory changed while awaiting review"
                        )
                    previous_body = (root / (record.memory_id + ".md")).read_bytes()
                    if content_hash(previous_body.decode("utf-8")) != previous["sha256"]:
                        raise StorageError(
                            "memory_corrupt", "Memory changed without matching metadata"
                        )
                    # Keep transaction/version paths short enough for Windows
                    # hosts whose long-path policy is not enabled.
                    backup = root / "versions" / uuid4().hex
                    atomic_write(backup / "content.md", previous_body)
                    atomic_write(backup / "record.json", metadata.read_bytes())
                elif expected_hash is not None:
                    raise StorageError(
                        "memory_version_conflict", "Memory was removed while awaiting review"
                    )
                data = plain_data(record)
                data.pop("content")
                data["summary"] = record.content[:160]
                data["sha256"] = content_hash(record.content)
                pending = root / "transactions" / uuid4().hex
                atomic_write(pending / "content.md", record.content.encode("utf-8"))
                atomic_write(
                    pending / "record.json",
                    json_dumps(data, ensure_ascii=False, sort_keys=True).encode(),
                )
                atomic_write(
                    pending / "manifest.json",
                    json_dumps(
                        {
                            "memory_id": record.memory_id,
                            "sha256": data["sha256"],
                            "status": "prepared",
                        },
                        sort_keys=True,
                    ).encode(),
                )
                self.recover_transactions(root)
                self.change(
                    root,
                    {
                        "action": "write",
                        "memory_id": record.memory_id,
                        "previous_hash": expected_hash,
                        "sha256": data["sha256"],
                        "timestamp": record.updated_at,
                    },
                )
                self.write_index(root)
            finally:
                lease.close()

        await owned_io(write)

    def change(self, root, record):
        path = root / "changes.jsonl"
        with path.open("ab") as output:
            output.write((json_dumps(record, ensure_ascii=False) + "\n").encode("utf-8"))
            output.flush()
            import os

            os.fsync(output.fileno())

    def write_index(self, root):
        lines = ["# Approved memory index", ""]
        for path in sorted(root.glob("*.json")):
            if re.fullmatch(r"[a-f0-9]{32}", path.stem):
                data = json.loads(path.read_bytes())
                lines.append(f"- [{data['key']}]({path.stem}.md) · {data['category']}")
        atomic_write(root / "MEMORY.md", "\n".join(lines).encode("utf-8"))

    async def remove(self, memory_id, *, expected_hash=None):
        def remove():
            root, previous = self.locate(memory_id)
            if previous["sha256"] != expected_hash:
                raise StorageError("memory_version_conflict", "Memory changed before removal")
            pending = root / "transactions" / uuid4().hex
            atomic_write(
                pending / "manifest.json",
                json_dumps(
                    {
                        "action": "remove",
                        "memory_id": memory_id,
                        "sha256": expected_hash,
                        "status": "prepared",
                    }
                ).encode(),
            )
            self.recover_transactions(root)

        await owned_io(self.read_consistent, remove)

    async def review(self, candidate_id, decision, timestamp):
        self.check_id(candidate_id)

        def review():
            for root in self.roots.values():
                path = root / "candidates" / (candidate_id + ".json")
                if not path.exists():
                    continue
                data = json.loads(path.read_bytes())
                data["status"] = decision
                atomic_write(path, json_dumps(data, ensure_ascii=False).encode("utf-8"))
                self.change(
                    root,
                    {
                        "action": "review",
                        "candidate_id": candidate_id,
                        "decision": decision,
                        "timestamp": timestamp,
                    },
                )
                return
            raise StorageError(
                "memory_candidate_not_found", "Candidate is not available for review"
            )

        await owned_io(self.read_consistent, review)

    async def set_mode(self, mode):
        if mode not in {"auto", "review", "off"}:
            raise StorageError("invalid_memory_mode", "Choose auto, review or off")
        await owned_io(
            atomic_write,
            self.roots["user"] / "settings.json",
            json_dumps({"mode": mode}).encode("utf-8"),
        )

    async def get_mode(self):
        def read():
            path = self.roots["user"] / "settings.json"
            return json.loads(path.read_bytes())["mode"] if path.exists() else "auto"

        return await owned_io(read)
