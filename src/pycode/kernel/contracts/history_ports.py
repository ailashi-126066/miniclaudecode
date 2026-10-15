"""Minimum read/append history capabilities shared across features."""

from typing import Protocol

from pycode.kernel.contracts.history import EntryDraft, SessionEntry, SessionSnapshot
from pycode.kernel.contracts.messages import Message


class MessageJournal(Protocol):
    async def append_message(self, message: Message) -> str: ...


class HistoryReader(Protocol):
    async def snapshot(self) -> SessionSnapshot: ...


class HistoryJournal(MessageJournal, HistoryReader, Protocol):
    async def append_entry(self, draft: EntryDraft) -> SessionEntry: ...
