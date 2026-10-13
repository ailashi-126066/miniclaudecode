"""Completed history persistence; framework messages never cross this boundary."""

from typing import Protocol

from pycode.core.messages import Message
from pycode.core.session import EntryDraft, SessionEntry, SessionSnapshot


class MessageJournal(Protocol):
    async def append_message(self, message: Message) -> str: ...


class SessionStore(MessageJournal, Protocol):
    async def snapshot(self) -> SessionSnapshot: ...

    async def append_entry(self, draft: EntryDraft) -> SessionEntry: ...

    async def branch(self, leaf_id: str | None) -> None: ...

    async def aclose(self) -> None: ...
