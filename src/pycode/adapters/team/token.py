"""Per-port bearer tokens for the local Worker host, kept private in user data."""

import os
import secrets
import tempfile
from pathlib import Path


def token_path(user_data, port):
    return Path(user_data) / "team-tokens" / f"{int(port)}.token"


def issue_token(user_data, port):
    """Create a fresh token and atomically publish it with owner-only permissions."""
    token = secrets.token_urlsafe(32)
    path = token_path(user_data, port)
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    # mkdir leaves an existing directory's mode alone; tighten it explicitly.
    path.parent.chmod(0o700)
    # mkstemp creates the file with 0600, so the secret is never world-readable.
    descriptor, temporary = tempfile.mkstemp(prefix=".token-", dir=path.parent)
    try:
        with os.fdopen(descriptor, "w", encoding="ascii") as output:
            output.write(token)
            output.flush()
            os.fsync(output.fileno())
        os.chmod(temporary, 0o600)
        os.replace(temporary, path)
    finally:
        Path(temporary).unlink(missing_ok=True)
    return token


def read_token(user_data, port):
    try:
        token = token_path(user_data, port).read_text(encoding="ascii").strip()
    except (OSError, UnicodeDecodeError):
        return None
    return token or None


def revoke_token(user_data, port, token):
    """Remove the published token only if a newer host has not replaced it."""
    if read_token(user_data, port) == token:
        token_path(user_data, port).unlink(missing_ok=True)
