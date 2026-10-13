"""Display names never participate in session identity or storage paths."""

from pycode.core.errors import StorageError


def validate_title(value):
    if not isinstance(value, str):
        raise StorageError("invalid_session_name", "Session name must be text")
    try:
        value.encode("utf-8")
    except UnicodeError as error:
        raise StorageError("invalid_session_name", "Session name must be valid UTF-8") from error
    title = " ".join(value.split())
    if not title or len(title) > 80:
        raise StorageError(
            "invalid_session_name", "Session name must contain one to eighty characters"
        )
    for character in title:
        if ord(character) < 32 or ord(character) == 127:
            raise StorageError(
                "invalid_session_name", "Session name cannot contain control characters"
            )
    return title


def session_title(snapshot):
    title = None
    for entry in snapshot.entries:
        if entry.type == "session_metadata" and "title" in entry.data:
            title = entry.data["title"]
    return title


def initial_title(text):
    title = " ".join(text.split())[:80]
    if not title:
        return None
    return validate_title(title)
