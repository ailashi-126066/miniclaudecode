"""Session operations preserve native branch ownership and original histories."""

from pycode.kernel import EntryDraft, StorageError, plain_data
from pycode.session.domain.session_names import initial_title, session_title, validate_title
from pycode.session.ports.sessions import SessionRepository


class SessionService:
    def __init__(self, repository: SessionRepository):
        self._repository = repository

    async def list_sessions(self):
        sessions = []
        for header in await self._repository.list_headers():
            if header.session_id.startswith("team-"):
                continue
            store = await self._repository.open(header.session_id)
            try:
                snapshot = await store.snapshot()
                title = session_title(snapshot) or "Untitled session"
                updated = snapshot.entries[-1].timestamp if snapshot.entries else header.created_at
                selection = None
                for entry in snapshot.entries:
                    if entry.selection is not None:
                        selection = entry.selection
                    elif entry.message is not None and entry.message.model_selection is not None:
                        selection = entry.message.model_selection
                sessions.append(
                    {
                        **plain_data(header),
                        "title": title,
                        "short_id": header.session_id[:8],
                        "updated_at": updated,
                        "model": plain_data(selection) if selection else None,
                    }
                )
            finally:
                await store.aclose()
        sessions.sort(key=lambda item: (item["updated_at"], item["session_id"]), reverse=True)
        return tuple(sessions)

    async def resolve(self, value):
        headers = await self._repository.list_headers()
        for header in headers:
            if header.session_id == value:
                return value
        matches = []
        for item in await self.list_sessions():
            if item["title"] == value:
                matches.append(item)
        if len(matches) == 1:
            return matches[0]["session_id"]
        if len(matches) > 1:
            identifiers = []
            for item in matches:
                identifiers.append(item["session_id"] + " (" + item["updated_at"] + ")")
            raise StorageError(
                "ambiguous_session_name", "Choose a session ID: " + ", ".join(identifiers)
            )
        raise StorageError("session_missing", "No session has this ID or exact name")

    async def rename(self, value, title):
        identifier = await self.resolve(value)
        store = await self._repository.open(identifier)
        try:
            await name_session(store, title)
            return {"session_id": identifier, "title": validate_title(title)}
        finally:
            await store.aclose()

    async def fork(self, source_id, leaf_id, target_id):
        store = await self._repository.fork(source_id, leaf_id, target_id)
        try:
            snapshot = await store.snapshot()
            title = session_title(snapshot) or source_id
            await name_session(store, title[:73] + " (fork)", automatic=False)
            return plain_data(await store.snapshot())
        finally:
            await store.aclose()


async def name_session(store, title, *, automatic=False):
    title = validate_title(title)
    snapshot = await store.snapshot()
    previous = session_title(snapshot)
    if automatic and previous is not None:
        return previous
    if previous == title:
        return title
    await store.append_entry(
        EntryDraft("session_metadata", data={"title": title, "automatic": automatic})
    )
    return title


async def assign_initial_title(store, message):
    if message.role != "user":
        return
    title = initial_title(message.text)
    if title is not None:
        await name_session(store, title, automatic=True)
