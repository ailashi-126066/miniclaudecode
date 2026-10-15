"""One task-level prompt snapshot, retained in canonical branch history."""

from pycode.kernel import EntryDraft, SessionTree


class PromptService:
    def __init__(self, history, source):
        self.history = history
        self.source = source

    async def refresh(self):
        current = await self.source.read()
        snapshot = await self.history.snapshot()
        previous = None
        for entry in SessionTree(snapshot.index).path(snapshot.leaf_id):
            if entry.type == "system_prompt":
                previous = entry.data
            elif entry.type == "compaction":
                previous = entry.data.get("checkpoint", {}).get("prompt_snapshot", previous)
        if previous == current:
            return previous
        await self.history.append_entry(EntryDraft("system_prompt", data=current))
        return current
