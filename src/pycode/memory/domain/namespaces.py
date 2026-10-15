"""Pure scope identities independent from filesystem resolution and configuration."""

import hashlib

from pycode.memory.domain.memory import scope_namespace


def scoped_memory_namespaces(user_data_identity: str, project_id: str):
    user_id = hashlib.sha256(user_data_identity.encode()).hexdigest()[:24]
    return scope_namespace("user", user_id), scope_namespace("project", project_id)


def automatic_namespaces(user_namespace, project_namespace, *, trusted: bool):
    return (user_namespace, project_namespace) if trusted else (user_namespace,)
