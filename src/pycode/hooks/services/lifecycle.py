"""Bind neutral agent lifecycle boundaries to configured hook contexts."""

from pycode.hooks.domain.hooks import HookContext
from pycode.hooks.ports.lifecycle import LifecycleContext


async def run_hook(
    context: LifecycleContext, event: str, boundary: str, *, message: str = "", error: str = ""
) -> None:
    hooks = context.hooks
    if hooks is not None:
        await hooks.fire(
            HookContext(
                context.session_id,
                context.run_id,
                context.workspace,
                boundary,
                event,
                message=message,
                error=error,
            )
        )
