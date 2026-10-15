"""Checked job records use the memory scope lease and shared owned atomic I/O."""

import json
from pathlib import Path

from pycode.adapters.storage.json_memory import encode, memory_lease, reject_duplicate_keys
from pycode.adapters.storage.path_io import atomic_write, owned_io, safe_relative
from pycode.core.errors import StorageError
from pycode.core.memory_jobs import MemoryJob


class FileMemoryJobs:
    def __init__(self, roots):
        self.roots = {namespace: Path(root).resolve() for namespace, root in roots.items()}

    def root(self, namespace):
        if namespace not in self.roots:
            raise StorageError("memory_scope_denied", "Job namespace is outside configured memory")
        return self.roots[namespace]

    def path(self, namespace, job_id):
        root = self.root(namespace)
        if len(job_id) != 64 or any(c not in "0123456789abcdef" for c in job_id):
            raise StorageError("invalid_memory_job_id", "Memory job identity is invalid")
        return safe_relative(root, "jobs/" + job_id + ".json")

    def read(self, namespace, job_id):
        path = self.path(namespace, job_id)
        if not path.exists():
            return None
        try:
            payload = json.loads(path.read_bytes(), object_pairs_hook=reject_duplicate_keys)
            job = MemoryJob.model_validate_json(encode(payload))
            if job.namespace != namespace or job.job_id != job_id:
                raise ValueError("Memory job belongs to another source or namespace")
            return job
        except (OSError, ValueError, TypeError, UnicodeError) as error:
            raise StorageError("memory_job_corrupt", "Memory job integrity check failed") from error

    def write(self, job, *, expected_revision=None):
        job = MemoryJob.model_validate_json(job.model_dump_json())
        path = self.path(job.namespace, job.job_id)
        with memory_lease(self.root(job.namespace)):
            previous = self.read(job.namespace, job.job_id)
            if expected_revision is None:
                if previous is not None:
                    return previous
                if job.revision != 0:
                    raise StorageError("memory_job_conflict", "A new job starts at revision zero")
            elif previous is None or previous.revision != expected_revision:
                raise StorageError("memory_job_conflict", "Memory job changed before publication")
            elif (
                previous.source_identity() != job.source_identity()
                or previous.run_id != job.run_id
                or previous.created_at != job.created_at
                or job.revision != expected_revision + 1
            ):
                raise StorageError("memory_job_conflict", "Memory job identity or version changed")
            payload = encode(job.model_dump(mode="json"))
            if len(payload) > 2 * 1024 * 1024:
                raise StorageError(
                    "memory_job_budget", "Memory job proposals exceed the disk budget"
                )
            atomic_write(path, payload)
            return job

    async def create(self, job):
        return await owned_io(self.write, job)

    async def get(self, namespace, job_id):
        return await owned_io(self.read, namespace, job_id)

    async def update(self, job, *, now, **changes):
        transitions = {
            "pending": {"running", "pending_retry", "completed", "obsolete"},
            "running": {"running", "proposed", "pending_retry", "completed", "obsolete"},
            "proposed": {"running", "pending_retry", "completed", "obsolete"},
            "pending_retry": {"running", "proposed", "completed", "obsolete"},
            "completed": set(),
            "obsolete": set(),
        }
        if changes.get("status") not in transitions[job.status]:
            raise StorageError("memory_job_closed", "Memory job transition is invalid")
        allowed = {
            "status",
            "proposals",
            "observed_revision",
            "observed_targets",
            "error_code",
            "reorganize_reason",
            "evaluations",
        }
        if not set(changes) <= allowed:
            raise StorageError("memory_job_conflict", "Job updates cannot replace source identity")
        value = job.model_dump(mode="json")
        value.update(changes, revision=job.revision + 1, updated_at=float(now))
        checked = MemoryJob.model_validate_json(encode(value))
        return await owned_io(lambda: self.write(checked, expected_revision=job.revision))

    async def pending(self, namespace, session_id, workspace):
        def read():
            root = self.root(namespace)
            directory = safe_relative(root, "jobs")
            if not directory.exists():
                return ()
            jobs = []
            for path in sorted(directory.glob("*.json")):
                job = self.read(namespace, path.stem)
                if (
                    job.session_id == session_id
                    and job.workspace == workspace
                    and job.status not in {"completed", "obsolete"}
                ):
                    jobs.append(job)
            return tuple(sorted(jobs, key=lambda job: (job.created_at, job.job_id)))

        return await owned_io(read)

    async def get_mode(self, namespace):
        def read():
            path = safe_relative(self.root(namespace), "settings.json")
            if not path.exists():
                return "auto"
            try:
                value = json.loads(path.read_bytes(), object_pairs_hook=reject_duplicate_keys)
                if (
                    not isinstance(value, dict)
                    or set(value) != {"mode"}
                    or value["mode"] not in {"auto", "review", "off"}
                ):
                    raise ValueError("Memory settings are invalid")
                return value["mode"]
            except (OSError, ValueError, TypeError, UnicodeError) as error:
                raise StorageError(
                    "invalid_memory_mode", "Memory settings cannot be read"
                ) from error

        return await owned_io(read)

    async def set_mode(self, namespace, mode):
        if mode not in {"auto", "off"}:
            raise StorageError("invalid_memory_mode", "Choose auto or off")

        def write():
            root = self.root(namespace)
            with memory_lease(root):
                atomic_write(safe_relative(root, "settings.json"), encode({"mode": mode}))

        await owned_io(write)
