"""Managed-object inventory, checked moves, 30-day quarantine and audit records."""

import hashlib
import json
import os
import re
import shutil
import subprocess
from dataclasses import replace
from pathlib import Path

from pycode.adapters.storage.file_lease import FileLease
from pycode.adapters.storage.layout import StorageLayout
from pycode.adapters.storage.memory_references import memory_reference_values
from pycode.adapters.storage.path_io import atomic_write, file_sha256, owned_io
from pycode.adapters.storage.session_codec import decode_entry, decode_header
from pycode.core.errors import StorageError
from pycode.core.storage import StorageInventory, StoredObject, mark_objects
from pycode.core.values import json_dumps, plain_data


def string_values(value):
    values = []
    pending = [value]
    while pending:
        item = pending.pop()
        if isinstance(item, str):
            values.append(item)
        elif isinstance(item, dict):
            pending.extend(item.values())
        elif isinstance(item, list):
            pending.extend(item)
    return values


class FileStorageObjects:
    def __init__(self, user_data: Path, workspace: Path, *, owned_session_id=None):
        self.root = user_data.resolve()
        self.workspace = workspace.resolve()
        self.project = StorageLayout(workspace, user_data).project.resolve()
        self.quarantine = self.root / "quarantine" / "objects"
        self.allowed_roots = [self.root, self.workspace / ".pycode" / "plans"]
        self.lease = FileLease(self.root / "storage.lock")
        self.owned_session_id = owned_session_id

    def checked(self, value):
        path = Path(value)
        resolved = path.resolve()
        for root in self.allowed_roots:
            if resolved.is_relative_to(root.resolve()) and resolved != root.resolve():
                return resolved
        raise StorageError("gc_path_escape", "Content operation escapes managed storage")

    def object_key(self, object_id):
        return hashlib.sha256(object_id.encode("utf-8")).hexdigest()

    def record_path(self, object_id):
        return self.quarantine / self.object_key(object_id) / "record.json"

    def read_json(self, path):
        try:
            return json.loads(self.checked(path).read_bytes())
        except (OSError, ValueError) as error:
            raise StorageError(
                "gc_unreadable_record", "Cannot verify managed storage references"
            ) from error

    def descriptor(self, object_id, kind, files, *, protected=False, metadata=None):
        owned = []
        size = 0
        for path in files:
            checked = self.checked(path)
            if checked.exists():
                if checked.is_symlink() or not checked.is_file():
                    raise StorageError("gc_invalid_object", "Managed objects must be regular files")
                owned.append(str(checked))
                size += checked.stat().st_size
        return StoredObject(
            object_id, kind, tuple(owned), size, protected=protected, metadata=metadata or {}
        )

    def inventory_sync(self):
        objects = {}
        tokens_by_object = {}
        root_tokens = []
        reference_details = {}
        aliases = {}
        uncertain_projects = set()
        active_projects = set()
        known_workspaces = {str(self.workspace)}
        projects = self.root / "projects"
        if projects.exists():
            for project in sorted(projects.iterdir()):
                if (
                    not re.fullmatch(r"[a-f0-9]{24}", project.name)
                    or not project.is_dir()
                    or project.is_symlink()
                ):
                    continue
                sessions = project / "sessions"
                workspaces = set()
                if sessions.exists():
                    for session in sorted(sessions.iterdir()):
                        if not session.is_dir() or session.is_symlink():
                            continue
                        log = session / "entries.jsonl"
                        if log.exists():
                            writer_lock = session / "writer.lock"
                            owned = (
                                project.resolve() == self.project
                                and session.name == self.owned_session_id
                            )
                            if writer_lock.exists() and not owned:
                                probe = FileLease(writer_lock)
                                try:
                                    probe.acquire()
                                except StorageError:
                                    active_projects.add(project.name)
                                finally:
                                    probe.close()
                            with log.open("rb") as source:
                                header = decode_header(source.readline())
                                if (
                                    header.session_id != session.name
                                    or StorageLayout(Path(header.workspace), self.root).project.name
                                    != project.name
                                ):
                                    raise StorageError(
                                        "gc_session_identity_conflict",
                                        "Session references belong to another project",
                                    )
                                workspaces.add(header.workspace)
                                known_workspaces.add(header.workspace)
                                for line in source:
                                    if not line.strip():
                                        continue
                                    try:
                                        entry = decode_entry(line)
                                    except StorageError:
                                        if not line.endswith(b"\n"):
                                            uncertain_projects.add(project.name)
                                            break
                                        raise
                                    tokens = string_values(plain_data(entry))
                                    root_tokens.extend(tokens)
                                    for token in tokens:
                                        previous = reference_details.get(token)
                                        if (
                                            previous is None
                                            or entry.timestamp >= previous["timestamp"]
                                        ):
                                            reference_details[token] = {
                                                "session_id": session.name,
                                                "entry_id": entry.id,
                                                "timestamp": entry.timestamp,
                                            }
                        results = session / "tool-results"
                        if results.exists():
                            for path in sorted(results.glob("output_*.json")):
                                if path.name.endswith(".artifact.json"):
                                    continue
                                data = self.read_json(path)
                                output_id = data["output_id"]
                                if (
                                    not re.fullmatch(r"output_[a-f0-9]{32}", output_id)
                                    or path.stem != output_id
                                    or data["session_id"] != session.name
                                ):
                                    raise StorageError(
                                        "gc_output_identity_conflict",
                                        "Output object identity cannot be verified",
                                    )
                                identifier = f"output:{project.name}:{session.name}:{output_id}"
                                files = [
                                    path,
                                    results / (output_id + ".txt"),
                                    results / (output_id + ".artifact.json"),
                                ]
                                item = self.descriptor(
                                    identifier,
                                    "output",
                                    files,
                                    protected=data.get("source_entry_id") is None
                                    or data.get("status") not in {"completed", "interrupted"},
                                    metadata={"project_id": project.name},
                                )
                                objects[identifier] = item
                                aliases.setdefault(output_id, []).append(identifier)
                            for partial in results.glob("*.partial"):
                                identifier = (
                                    "partial:" + hashlib.sha256(str(partial).encode()).hexdigest()
                                )
                                objects[identifier] = self.descriptor(
                                    identifier,
                                    "partial",
                                    [partial],
                                    protected=True,
                                    metadata={"project_id": project.name},
                                )
                summaries = project / "summaries"
                if summaries.exists():
                    grouped = {}
                    for path in sorted(summaries.glob("*.json")):
                        job_id = path.name.split(".", 1)[0]
                        if re.fullmatch(r"[a-f0-9]{32}", job_id):
                            grouped.setdefault(job_id, []).append(path)
                    for job_id, files in grouped.items():
                        identifier = f"summary:{project.name}:{job_id}"
                        objects[identifier] = self.descriptor(
                            identifier,
                            "summary",
                            files,
                            protected=not any(
                                path.name.endswith((".result.json", ".invalidated.json"))
                                for path in files
                            ),
                            metadata={"project_id": project.name},
                        )
                        aliases.setdefault(job_id, []).append(identifier)
                        tokens = []
                        for path in files:
                            tokens.extend(string_values(self.read_json(path)))
                        tokens_by_object[identifier] = tokens
                trees = project / "snapshots" / "trees"
                if trees.exists():
                    for path in sorted(trees.glob("*.json")):
                        if not re.fullmatch(r"[a-f0-9]{40}", path.stem):
                            continue
                        identifier = f"snapshot:{project.name}:{path.stem}"
                        objects[identifier] = self.descriptor(
                            identifier,
                            "snapshot",
                            [path],
                            metadata={
                                "project_id": project.name,
                                "tree_id": path.stem,
                                "gitdir": str(project / "snapshots" / "repository.git"),
                            },
                        )
                        aliases.setdefault(path.stem, []).append(identifier)
                restores = project / "snapshots" / "restores"
                if restores.exists():
                    for path in restores.glob("*.json"):
                        root_tokens.extend(string_values(self.read_json(path)))
                for workspace in workspaces:
                    plans = Path(workspace).resolve() / ".pycode" / "plans"
                    if plans not in self.allowed_roots:
                        self.allowed_roots.append(plans)
                    if not plans.exists():
                        continue
                    for path in sorted(plans.glob("*/*.md")):
                        if not re.fullmatch(r"[a-f0-9]{32}", path.stem):
                            continue
                        identifier = f"plan:{project.name}:{path.parent.name}:{path.stem}"
                        objects[identifier] = self.descriptor(
                            identifier, "plan", [path], metadata={"project_id": project.name}
                        )
                        aliases.setdefault(str(path.resolve()), []).append(identifier)
                        aliases.setdefault(path.stem, []).append(identifier)
        if projects.exists():
            for project in sorted(projects.iterdir()):
                deleted = project / "quarantine" / "sessions"
                if not deleted.exists():
                    continue
                for record in sorted(deleted.glob("*/record.json")):
                    data = self.read_json(record)
                    if data["status"] != "quarantined":
                        continue
                    identifier = f"session:{project.name}:{data['session_id']}"
                    files = []
                    size = 0
                    for moved in data["moved"]:
                        path = self.checked(moved["quarantine"])
                        if path.exists():
                            files.append(str(path))
                            size += path.stat().st_size
                    objects[identifier] = StoredObject(
                        identifier,
                        "session",
                        tuple(files),
                        size,
                        quarantined_at=data["quarantined_at"],
                        metadata={
                            "session_record": str(record),
                            "session_id": data["session_id"],
                            "project_id": project.name,
                        },
                    )
                    aliases.setdefault(data["session_id"], []).append(identifier)
                    references = []
                    for filename in files:
                        if Path(filename).name != "entries.jsonl":
                            continue
                        with Path(filename).open("rb") as stream:
                            stream.readline()
                            for raw in stream:
                                if not raw.endswith(b"\n"):
                                    raise StorageError(
                                        "gc_incomplete_deleted_session",
                                        "Deleted history has a partial tail",
                                    )
                                references.extend(string_values(plain_data(decode_entry(raw))))
                    tokens_by_object[identifier] = references
        memory_roots = [self.root / "memory"]
        for workspace in known_workspaces:
            root = Path(workspace).resolve() / ".pycode" / "memory"
            memory_roots.append(root)
            if root not in self.allowed_roots:
                self.allowed_roots.append(root)
        memory_writing = False
        for root in memory_roots:
            values, busy = memory_reference_values(root)
            for value in values:
                root_tokens.extend(string_values(value))
            if busy:
                if root == self.root / "memory":
                    memory_writing = True
                else:
                    active_projects.add(StorageLayout(root.parent.parent, self.root).project.name)
        if self.quarantine.exists():
            for path in sorted(self.quarantine.glob("*/record.json")):
                data = self.read_json(path)
                identifier = data["object_id"]
                if data["status"] != "quarantined":
                    continue
                # Records retain original paths so restored references resolve to
                # the same identity, without making quarantine a second history.
                stored = StoredObject(
                    identifier,
                    data["kind"],
                    tuple(data["files"]),
                    data["size"],
                    quarantined_at=data["quarantined_at"],
                    protected=data.get("protected", False),
                    metadata=data.get("metadata", {}),
                )
                objects[identifier] = stored
                alias = stored.metadata.get("alias")
                if alias:
                    aliases.setdefault(alias, []).append(identifier)
                tokens = []
                for moved in data["moved"]:
                    target = self.checked(moved["quarantine"])
                    if target.suffix == ".json" and target.exists():
                        tokens.extend(string_values(self.read_json(target)))
                tokens_by_object[identifier] = tokens
        roots = set()
        last_references = {}
        for token in root_tokens:
            identifiers = aliases.get(token, ())
            roots.update(identifiers)
            detail = reference_details.get(token)
            if detail is not None:
                for identifier in identifiers:
                    previous = last_references.get(identifier)
                    if previous is None or detail["timestamp"] >= previous["timestamp"]:
                        last_references[identifier] = detail
        for identifier, item in list(objects.items()):
            references = set()
            for token in tokens_by_object.get(identifier, ()):
                references.update(aliases.get(token, ()))
            protected = (
                item.protected
                or memory_writing
                or item.metadata.get("project_id") in uncertain_projects
                or item.metadata.get("project_id") in active_projects
            )
            objects[identifier] = replace(
                item,
                references=tuple(sorted(references)),
                protected=protected,
                metadata={
                    **item.metadata,
                    "last_reference": last_references.get(
                        identifier, item.metadata.get("last_reference")
                    ),
                },
            )
        return StorageInventory(tuple(objects.values()), tuple(sorted(roots)))

    async def inventory(self):
        return await owned_io(self.inventory_sync)

    def audit_sync(self, data):
        path = self.root / "storage-audit.jsonl"
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open("ab") as output:
            output.write(
                (json_dumps(data, ensure_ascii=False, sort_keys=True) + "\n").encode("utf-8")
            )
            output.flush()
            os.fsync(output.fileno())

    async def audit(self, data):
        await owned_io(self.audit_sync, data)

    def move_checked(self, source, target):
        source = self.checked(source)
        target = self.checked(target)
        target.parent.mkdir(parents=True, exist_ok=True)
        if target.exists():
            if file_sha256(source) != file_sha256(target):
                raise StorageError(
                    "gc_restore_conflict", "Destination object has different contents"
                )
            source.unlink()
            return
        try:
            os.replace(source, target)
        except OSError as error:
            if error.errno != 18:
                raise
            shutil.copyfile(source, target)
            with target.open("rb") as copied:
                os.fsync(copied.fileno())
            if file_sha256(source) != file_sha256(target):
                raise StorageError(
                    "gc_copy_failed", "Cross-filesystem quarantine copy failed verification"
                ) from error
            source.unlink()

    def purge_snapshot_ref(self, item):
        gitdir = self.checked(item.metadata["gitdir"])
        expected = (
            self.root / "projects" / item.metadata["project_id"] / "snapshots" / "repository.git"
        )
        if gitdir != expected.resolve():
            raise StorageError(
                "gc_git_scope", "Git GC must use the independent snapshot repository"
            )
        command = ["git", "--git-dir=" + str(gitdir)]
        environment = {}
        for key, value in os.environ.items():
            if not key.startswith("GIT_"):
                environment[key] = value
        environment["GIT_CONFIG_NOSYSTEM"] = "1"
        environment["GIT_CONFIG_GLOBAL"] = os.devnull
        bare = subprocess.run(
            [*command, "rev-parse", "--is-bare-repository"],
            capture_output=True,
            check=True,
            env=environment,
        )
        if bare.stdout.strip() != b"true":
            raise StorageError("gc_git_scope", "Snapshot repository is not bare")
        subprocess.run(
            [*command, "update-ref", "-d", "refs/pycode/snapshots/" + item.metadata["tree_id"]],
            capture_output=True,
            check=True,
            env=environment,
        )
        subprocess.run(
            [*command, "gc", "--prune=now"], capture_output=True, check=True, env=environment
        )

    async def apply(self, item, action, timestamp):
        def mutate():
            self.lease.acquire()
            snapshot_lease = None
            try:
                if item.kind == "snapshot":
                    snapshot_lease = FileLease(
                        Path(item.metadata["gitdir"]).parent / "snapshot.lock"
                    )
                    snapshot_lease.acquire()
                if action in {"quarantine", "purge"}:
                    current = self.inventory_sync()
                    if item.object_id in mark_objects(current):
                        raise StorageError(
                            "gc_reference_changed", "Object gained a reference during collection"
                        )
                    for candidate in current.objects:
                        if candidate.object_id == item.object_id and candidate.protected:
                            raise StorageError(
                                "gc_reference_changed",
                                "Object gained an active owner during collection",
                            )
                if item.kind == "session":
                    record = self.checked(item.metadata["session_record"])
                    data = self.read_json(record)
                    if action != "purge":
                        raise StorageError(
                            "invalid_session_gc_action",
                            "Deleted sessions require explicit restoration",
                        )
                    for moved in data["moved"]:
                        self.checked(moved["quarantine"]).unlink(missing_ok=True)
                    data["status"] = "purged"
                    data["purged_at"] = timestamp
                    atomic_write(record, json_dumps(data).encode("utf-8"))
                    return
                record = self.record_path(item.object_id)
                if action == "quarantine":
                    moved = []
                    for index, original in enumerate(item.files):
                        path = self.checked(original)
                        target = record.parent / (str(index) + path.suffix)
                        moved.append({"original": str(path), "quarantine": str(target)})
                    metadata = dict(item.metadata)
                    if item.kind == "output":
                        metadata["alias"] = item.object_id.rsplit(":", 1)[1]
                    elif item.kind in {"summary", "snapshot", "plan"}:
                        metadata["alias"] = item.object_id.rsplit(":", 1)[1]
                    data = {
                        "object_id": item.object_id,
                        "kind": item.kind,
                        "files": list(item.files),
                        "moved": moved,
                        "size": item.size,
                        "metadata": metadata,
                        "quarantined_at": timestamp,
                        "status": "quarantined",
                    }
                    atomic_write(record, json_dumps(data, sort_keys=True).encode("utf-8"))
                    for entry in moved:
                        if Path(entry["original"]).exists():
                            self.move_checked(entry["original"], entry["quarantine"])
                else:
                    data = self.read_json(record)
                    if action == "restore":
                        for entry in data["moved"]:
                            if Path(entry["quarantine"]).exists():
                                self.move_checked(entry["quarantine"], entry["original"])
                        data["status"] = "restored"
                    elif action == "purge":
                        if item.kind == "snapshot":
                            self.purge_snapshot_ref(item)
                        for entry in data["moved"]:
                            self.checked(entry["quarantine"]).unlink(missing_ok=True)
                        data["status"] = "purged"
                    else:
                        raise StorageError("invalid_gc_action", "Unknown object lifecycle action")
                    data["updated_at"] = timestamp
                    atomic_write(record, json_dumps(data, sort_keys=True).encode("utf-8"))
            finally:
                if snapshot_lease is not None:
                    snapshot_lease.close()
                self.lease.close()

        await owned_io(mutate)

    def session_record(self, session_id):
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,127}", session_id):
            raise StorageError("invalid_session_id", "Session ID must be an opaque identity")
        return self.project / "quarantine" / "sessions" / session_id / "record.json"

    async def quarantine_session(self, session_id, timestamp):
        def quarantine():
            self.lease.acquire()
            try:
                directory = self.project / "sessions" / session_id
                record = self.session_record(session_id)
                moved = []
                with (directory / "entries.jsonl").open("rb") as source:
                    header_bytes = source.readline()
                atomic_write(self.project / "session-info" / (session_id + ".json"), header_bytes)
                for name in ("entries.jsonl", "position.json"):
                    source = directory / name
                    if source.exists():
                        moved.append(
                            {"original": str(source), "quarantine": str(record.parent / name)}
                        )
                data = {
                    "session_id": session_id,
                    "status": "quarantined",
                    "quarantined_at": timestamp,
                    "moved": moved,
                }
                atomic_write(record, json_dumps(data).encode("utf-8"))
                for entry in moved:
                    self.move_checked(entry["original"], entry["quarantine"])
                self.audit_sync(
                    {
                        "action": "session_quarantine",
                        "session_id": session_id,
                        "timestamp": timestamp,
                        "result": "completed",
                    }
                )
            finally:
                self.lease.close()

        await owned_io(quarantine)

    async def mark_session_deleted(self, session_id, timestamp):
        data = {"session_id": session_id, "status": "quarantining", "quarantined_at": timestamp}
        await owned_io(
            atomic_write, self.session_record(session_id), json_dumps(data).encode("utf-8")
        )

    async def restore_session(self, session_id, timestamp):
        def restore():
            self.lease.acquire()
            try:
                record = self.session_record(session_id)
                data = self.read_json(record)
                if data["status"] != "quarantined":
                    raise StorageError(
                        "session_restore_unavailable", "Session is not in quarantine"
                    )
                for entry in data["moved"]:
                    self.move_checked(entry["quarantine"], entry["original"])
                data["status"] = "restored"
                data["restored_at"] = timestamp
                atomic_write(record, json_dumps(data).encode("utf-8"))
                self.audit_sync(
                    {
                        "action": "session_restore",
                        "session_id": session_id,
                        "timestamp": timestamp,
                        "result": "completed",
                    }
                )
            finally:
                self.lease.close()

        await owned_io(restore)
