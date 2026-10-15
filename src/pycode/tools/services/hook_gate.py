"""Configured hook effects remain constrained by read-only and Plan policies."""

from pycode.kernel import ToolError


class ConfiguredActionGate:
    def __init__(self, plans=None, read_only=False):
        self.plans = plans
        self.read_only = read_only

    async def authorize(self, context, spec, call):
        if not spec.source.startswith("hook:"):
            raise ToolError("hook_not_configured", "Only configured Hook actions can run here")
        if self.read_only and spec.category != "read":
            raise ToolError(
                "readonly_hook_denied", "Read-only subagents cannot run effectful Hooks"
            )
        if self.plans is not None:
            await self.plans.guard(context, spec, call)
