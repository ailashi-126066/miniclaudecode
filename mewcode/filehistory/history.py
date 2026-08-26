# 来源：公众号@小林coding
# 后端八股网站：xiaolincoding.com
# Agent网站：xiaolinnote.com
# 简历模版：jianli.xiaolinnote.com

from __future__ import annotations

import hashlib
import json
import os
import threading
import time
from dataclasses import dataclass, field
from pathlib import Path

MAX_SNAPSHOTS = 100


@dataclass
class Backup:
    backup_path: str
    version: int
    timestamp: float


@dataclass
class Snapshot:
    message_index: int
    user_text: str
    backups: dict[str, Backup] = field(default_factory=dict)
    timestamp: float = 0.0


class FileHistory:

    def __init__(self, base_dir: str, session_id: str) -> None:
        self._session_dir = Path(base_dir) / ".mewcode" / "file-history" / session_id
        self._session_dir.mkdir(parents=True, exist_ok=True)
        self._metadata_path = self._session_dir / "snapshots.json"
        self._tracked: dict[str, int] = {}
        self._snapshots: list[Snapshot] = []
        self._lock = threading.Lock()
        self._load_snapshots()

    def _load_snapshots(self) -> None:
        """Restore checkpoint metadata so /rewind also works after a restart."""
        try:
            raw = json.loads(self._metadata_path.read_text(encoding="utf-8"))
            for item in raw:
                backups = {
                    path: Backup(**backup)
                    for path, backup in item.get("backups", {}).items()
                }
                snapshot = Snapshot(
                    message_index=int(item["message_index"]),
                    user_text=str(item.get("user_text", "")),
                    backups=backups,
                    timestamp=float(item.get("timestamp", 0)),
                )
                self._snapshots.append(snapshot)
                for path, backup in backups.items():
                    self._tracked[path] = max(self._tracked.get(path, 0), backup.version)
        except (OSError, ValueError, TypeError, KeyError):
            self._snapshots = []

    def _persist_snapshots(self) -> None:
        payload = [
            {
                "message_index": snapshot.message_index,
                "user_text": snapshot.user_text,
                "timestamp": snapshot.timestamp,
                "backups": {
                    path: {
                        "backup_path": backup.backup_path,
                        "version": backup.version,
                        "timestamp": backup.timestamp,
                    }
                    for path, backup in snapshot.backups.items()
                },
            }
            for snapshot in self._snapshots
        ]
        temp_path = self._metadata_path.with_suffix(".tmp")
        temp_path.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
        temp_path.replace(self._metadata_path)

    def _backup_name(self, file_path: str, version: int) -> str:
        h = hashlib.sha256(file_path.encode()).hexdigest()[:16]
        return f"{h}@v{version}"

    def track_edit(self, path: str) -> None:
        with self._lock:
            abs_path = str(Path(path).resolve())
            ver = self._tracked.get(abs_path, 0)
            new_ver = ver + 1

            try:
                data = Path(abs_path).read_bytes()
                bp = self._session_dir / self._backup_name(abs_path, new_ver)
                bp.write_bytes(data)
            except FileNotFoundError:
                pass

            self._tracked[abs_path] = new_ver

    def make_snapshot(self, msg_index: int, user_text: str) -> None:
        with self._lock:
            backups: dict[str, Backup] = {}
            for path, ver in self._tracked.items():
                bp = self._session_dir / self._backup_name(path, ver)
                if not bp.exists():
                    try:
                        data = Path(path).read_bytes()
                        bp.write_bytes(data)
                    except (FileNotFoundError, OSError):
                        pass
                backups[path] = Backup(
                    backup_path=str(bp), version=ver, timestamp=time.time(),
                )

            self._snapshots.append(Snapshot(
                message_index=msg_index,
                user_text=user_text,
                backups=backups,
                timestamp=time.time(),
            ))
            if len(self._snapshots) > MAX_SNAPSHOTS:
                self._snapshots = self._snapshots[-MAX_SNAPSHOTS:]
            self._persist_snapshots()

    def get_snapshots(self) -> list[Snapshot]:
        with self._lock:
            return list(self._snapshots)

    def has_snapshots(self) -> bool:
        with self._lock:
            return len(self._snapshots) > 0

    def rewind(self, snapshot_index: int) -> list[str]:
        with self._lock:
            if snapshot_index < 0 or snapshot_index >= len(self._snapshots):
                return []

            target = self._snapshots[snapshot_index]
            changed: list[str] = []

            for file_path, backup in target.backups.items():
                bp = Path(backup.backup_path)
                try:
                    backup_data = bp.read_bytes()
                except FileNotFoundError:
                    fp = Path(file_path)
                    if fp.exists():
                        fp.unlink()
                        changed.append(file_path)
                    continue

                fp = Path(file_path)
                try:
                    current_data = fp.read_bytes()
                except FileNotFoundError:
                    current_data = b""

                if current_data != backup_data:
                    fp.parent.mkdir(parents=True, exist_ok=True)
                    fp.write_bytes(backup_data)
                    changed.append(file_path)

            self._snapshots = self._snapshots[: snapshot_index + 1]
            for file_path, backup in target.backups.items():
                self._tracked[file_path] = backup.version

            self._persist_snapshots()

            return changed
