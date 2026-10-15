# Source: MewCode local hooks/engine.py; fixed local inventory in docs/source-map.md.
# SHA-256: 91e9d3162556ea54e9c1d211feb3ecf4126a302f50f7eb3e5f584194be9e9b29
# Adaptation: append-only results, common operation recovery and owned background tasks.
# 来源：公众号@小林coding
# 后端八股网站：xiaolincoding.com
# Agent网站：xiaolinnote.com
# 简历模版：jianli.xiaolinnote.com
"""Hook actions never approve model tools or edit existing conversation messages."""

import asyncio
import contextvars

from pycode.core.errors import StorageError, ToolError
from pycode.core.hooks import HookContext
from pycode.core.messages import ToolCall, ToolResult
from pycode.core.session import EntryDraft
from pycode.core.summary_source import data_hash
from pycode.core.tools import ToolContext, ToolSpec
from pycode.core.values import clone_value, plain_data
from pycode.ports.hooks import HookActions
from pycode.services.tool_executor import ToolExecutor
from pycode.services.tool_registry import ToolRegistry


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


class HookActionTool:
    def __init__(self, hook, actions: HookActions):
        self.spec = ToolSpec(
            "hook__" + hook.id,
            "Configured lifecycle action",
            {
                "type": "object",
                "properties": {"context": {"type": "object"}, "action": {"type": "object"}},
                "required": ["context", "action"],
                "additionalProperties": False,
            },
            source="hook:" + hook.source,
            category="read"
            if hook.action.type in {"prompt", "agent"}
            else "command"
            if hook.action.type == "command"
            else "external",
            implementation_hash=data_hash(plain_data(hook)),
        )
        self._actions = actions

    async def execute(self, context, arguments):
        return await self._actions.execute(
            arguments["action"], HookContext(**arguments["context"]), context
        )


class HookEngine:
    def __init__(
        self,
        hooks,
        history,
        actions,
        validator,
        outputs,
        events,
        *,
        error_boundary,
        snapshots=None,
        owned_tasks=None,
        plans=None,
        read_only=False,
    ):
        self._hooks = hooks
        self._history = history
        self._actions = actions
        self._outputs = outputs
        self._events = events
        registry = ToolRegistry(validator, tuple(HookActionTool(hook, actions) for hook in hooks))
        self._executor = ToolExecutor(
            registry,
            validator,
            ConfiguredActionGate(plans, read_only),
            events,
            outputs,
            snapshots=snapshots,
            error_boundary=error_boundary,
        )
        self._tasks = owned_tasks if owned_tasks is not None else {}
        self._locks = {}
        self.configuration_hash = data_hash([plain_data(hook) for hook in hooks])

    async def fire(self, context):
        for hook in self._hooks:
            if hook.event != context.event_name:
                continue
            key = data_hash(
                {
                    "session": context.session_id,
                    "hook": plain_data(hook),
                    "boundary": "once" if hook.once else context.boundary_id,
                }
            )
            if hook.background:
                if key not in self._tasks:
                    self._tasks[key] = asyncio.create_task(
                        self.execute_background(hook, clone_value(context), key),
                        name="pycode-background-hook",
                        context=contextvars.Context(),
                    )
                continue
            if not await self._actions.matches(hook, context):
                continue
            result = await self.execute_one(hook, context, key)
            if result.is_error:
                raise ToolError("hook_failed", "Configured Hook failed: " + result.text)
            if hook.reject:
                return result.text or "Configured Hook rejected this tool"
        return None

    async def execute_background(self, hook, context, key):
        if await self._actions.matches(hook, context):
            return await self.execute_one(hook, context, key)
        return None

    async def execute_one(self, hook, context, key):
        lock = self._locks.setdefault(key, asyncio.Lock())
        async with lock:
            operation_id = "hook:" + key
            snapshot = await self._history.snapshot()
            previous = None
            started = False
            for entry in snapshot.entries:
                if entry.operation_id != operation_id:
                    continue
                if entry.type in {"hook_result", "hook_review_result"}:
                    previous = entry.data["result"]
                elif entry.type == "hook_call":
                    started = True
            if previous is not None:
                return ToolResult(**previous)
            tool_context = ToolContext(
                context.session_id,
                context.run_id,
                operation_id,
                context.workspace,
                call_id=operation_id,
                tool_name="hook__" + hook.id,
                agent_id="hook",
            )
            call = ToolCall(
                operation_id,
                tool_context.tool_name,
                {"context": plain_data(context), "action": plain_data(hook.action)},
            )
            if started:
                result = ToolResult(
                    call.id,
                    call.name,
                    "Hook interrupted; previous external effects are unknown",
                    is_error=True,
                    error_code="hook_interrupted",
                    session_id=context.session_id,
                    run_id=context.run_id,
                    operation_id=operation_id,
                )
            else:
                # hook_call is a durable JSONL boundary even before first input.
                await self._history.append_entry(
                    EntryDraft(
                        "hook_call",
                        data={"hook": plain_data(hook), "context": plain_data(context)},
                        run_id=context.run_id,
                        operation_id=operation_id,
                    )
                )
                result = await self._executor.execute(tool_context, call, "tool_calls")
            entry = await self._history.append_entry(
                EntryDraft(
                    "hook_result",
                    data={
                        "hook_id": hook.id,
                        "event": context.event_name,
                        "boundary_id": context.boundary_id,
                        "result": plain_data(result),
                    },
                    run_id=context.run_id,
                    operation_id=operation_id,
                )
            )
            if result.output_id:
                await self._outputs.bind(result.output_id, entry.id)
            if result.details.get("file_snapshot"):
                await self._history.append_entry(
                    EntryDraft(
                        "custom",
                        data={
                            "kind": "file_snapshot",
                            "message_entry_id": entry.id,
                            **result.details["file_snapshot"],
                        },
                        run_id=context.run_id,
                        operation_id=operation_id,
                    )
                )
            if hook.action.type == "prompt" and not result.is_error:
                await self._history.append_entry(
                    EntryDraft(
                        "hook_context",
                        data={
                            "text": result.text,
                            "hook_id": hook.id,
                            "operation_id": operation_id,
                        },
                        run_id=context.run_id,
                    )
                )
            await self._executor.completed(tool_context, call, result)
            await self._events.emit(
                "notification",
                {
                    "kind": "hook",
                    "hook_id": hook.id,
                    "event": context.event_name,
                    "success": not result.is_error,
                    "message": result.text,
                    "output_id": result.output_id,
                },
            )
            return result

    async def collect(self, *, cancel=False):
        tasks = tuple(self._tasks.values())
        if cancel:
            for task in tasks:
                if not task.done():
                    task.cancel()
        results = await asyncio.gather(*tasks, return_exceptions=True)
        self._tasks.clear()
        for result in results:
            if isinstance(result, BaseException) and not isinstance(result, asyncio.CancelledError):
                raise result

    async def aclose(self):
        await self.collect(cancel=True)

    async def reconcile(self, operation_id, action, text="", is_error=False):
        snapshot = await self._history.snapshot()
        original = None
        for entry in snapshot.entries:
            if entry.type == "hook_call" and entry.operation_id == operation_id:
                original = entry
                break
        if original is None:
            raise StorageError("hook_review_unavailable", "No Hook call is available for review")
        if action not in {"result", "retry", "abandon"}:
            raise StorageError("invalid_reconciliation", "Choose result, retry or abandon")
        review = await self._history.append_entry(
            EntryDraft(
                "hook_review",
                data={"action": action, "text": text, "is_error": is_error},
                operation_id=operation_id,
            )
        )
        if action != "retry":
            context = HookContext(**original.data["context"])
            result = ToolResult(
                operation_id,
                "hook__" + original.data["hook"]["id"],
                text if action == "result" else "Hook abandoned; effects remain unknown",
                is_error=is_error if action == "result" else True,
                error_code=None if action == "result" else "hook_interrupted",
                session_id=context.session_id,
                run_id=context.run_id,
                operation_id=operation_id,
                details={"verified_by_user": action == "result", "review_entry_id": review.id},
            )
            await self._history.append_entry(
                EntryDraft(
                    "hook_review_result",
                    data={"result": plain_data(result)},
                    operation_id=operation_id,
                )
            )
            return
        hook = None
        for candidate in self._hooks:
            if plain_data(candidate) == original.data["hook"]:
                hook = candidate
                break
        if hook is None:
            raise StorageError(
                "hook_configuration_changed", "Restore the matching Hook before retry"
            )
        context = HookContext(**original.data["context"])
        retry_id = data_hash(
            {"operation": operation_id, "review_entry": (await self._history.snapshot()).leaf_id}
        )
        await self.execute_one(hook, context, retry_id)
