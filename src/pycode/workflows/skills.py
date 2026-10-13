"""A catalogue budget boundary around the public native SkillsMiddleware loader."""

from deepagents.middleware.skills import SkillsMiddleware


class PycodeSkillsMiddleware(SkillsMiddleware):
    def __init__(self, *, selection=None, model_boundary=None, **options):
        super().__init__(**options)
        self.selection = selection
        self.loaded_revision = selection.revision if selection is not None else 0
        self.model_boundary = model_boundary

    @property
    def name(self):
        # Replace the harness's SkillsMiddleware slot; there is one loader.
        return "SkillsMiddleware"

    async def abefore_model(self, state, runtime):
        updates = {}
        if self.selection is not None and self.selection.revision != self.loaded_revision:
            loaded = await super().abefore_agent({**state, "skills_metadata": None}, runtime, {})
            updates.update(loaded or {})
            self.loaded_revision = self.selection.revision
        pinned = await super().abefore_model({**state, **updates}, runtime)
        updates.update(pinned or {})
        return updates or None

    async def awrap_model_call(self, request, handler):
        async def disclosed_request(current):
            if self.model_boundary is not None:
                current = await self.model_boundary(current)
            return await handler(current)

        return await super().awrap_model_call(request, disclosed_request)
