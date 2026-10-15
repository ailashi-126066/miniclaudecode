"""Non-interactive commands invoke the same public services as the UI."""

import asyncio
import json
from pathlib import Path
from uuid import uuid4

from pydantic import TypeAdapter, ValidationError

from pycode.app.config import load_providers
from pycode.app.container import (
    build_console_output,
    build_memory_service,
    build_session_repository,
    build_session_service,
    build_storage_gc,
    build_worktree_manager,
    open_agent_session,
    open_session_view,
)
from pycode.core.errors import DomainError
from pycode.core.messages import Message
from pycode.core.runtime import RunResult
from pycode.core.snapshots import RevertPlan
from pycode.core.values import json_dumps, plain_data


def leaf_id(value):
    return None if value == "root" else value


def read_providers(path):
    return load_providers(Path(path).expanduser().resolve())


def read_json(path):
    try:
        return json.loads(Path(path).expanduser().read_text(encoding="utf-8-sig"))
    except (OSError, UnicodeError, ValueError) as error:
        raise DomainError("invalid_json_file", "Cannot read UTF-8 JSON file") from error


def read_plan(path):
    try:
        data = json_dumps(read_json(path), allow_nan=False)
        plan = TypeAdapter(RevertPlan).validate_json(data, strict=True)
        if not plan.transaction_id or (plan.mode == "dialogue" and plan.files is not None):
            raise ValueError("Invalid revert plan")
        return plan
    except (ValidationError, ValueError) as error:
        raise DomainError("invalid_revert_plan", "Cannot validate the saved revert plan") from error


def write_plan(path, plan):
    try:
        with Path(path).expanduser().open("x", encoding="utf-8") as stream:
            json.dump(plain_data(plan), stream, ensure_ascii=False, allow_nan=False)
            stream.write("\n")
    except OSError as error:
        raise DomainError(
            "plan_export_failed", "Cannot create plan file; use a new path"
        ) from error


async def command(args, config):
    output = build_console_output(args.output_format)
    if args.command == "worktree":
        worktrees = build_worktree_manager(config)
        if args.worktree_command == "inspect":
            value = await worktrees.inspect(args.worktree_id)
        elif args.worktree_command == "confirm-integration":
            await worktrees.mark_integration(
                args.worktree_id, validated=args.validated, worker_stopped=args.worker_stopped
            )
            value = await worktrees.cleanup(args.worktree_id)
        else:
            value = await worktrees.scan()
        output.value(value)
        return 0
    if args.command in {"storage", "/storage"}:
        storage = build_storage_gc(config)
        if args.storage_command == "status":
            value = await storage.status()
        elif args.storage_command == "gc":
            value = await storage.collect(dry_run=args.dry_run)
        elif args.storage_command == "delete-session":
            await storage.delete_session(args.session_id)
            value = {"session_id": args.session_id, "deleted": True}
        else:
            value = await storage.restore_session(args.session_id)
        output.value(value)
        return 0
    if args.command in {"usage", "/usage", "session", "/session"}:
        return await view_command(args, config, output)
    if args.command in {"memory", "/memory"} and args.providers is None:
        if args.session is None:
            raise DomainError("session_required", "Memory commands require --session")
        repository = build_session_repository(config)
        store = await repository.open(args.session)
        memory = build_memory_service(config, store)
        try:
            await memory.initialize()
            if args.memory_command == "list":
                value = await memory.list()
            elif args.memory_command == "mode":
                await memory.set_mode(args.mode)
                value = {"mode": args.mode}
            elif args.memory_command == "remove":
                await memory.remove(args.memory_id)
                value = {"removed": args.memory_id}
            elif args.memory_command == "edit":
                value = {
                    "memory_id": await memory.edit(
                        args.memory_id, args.content, scope=args.scope, from_scope=args.from_scope
                    )
                }
            elif args.memory_command == "move":
                value = {
                    "memory_id": await memory.move(
                        args.memory_id, args.scope, from_scope=args.from_scope
                    ),
                    "scope": args.scope,
                }
            elif args.memory_command == "clear":
                await memory.clear()
                value = {"cleared": True}
            else:
                raise DomainError("providers_required", "Memory extraction requires --providers")
            output.value(value)
            return 0
        finally:
            try:
                await memory.aclose()
            finally:
                await store.aclose()
    if args.providers is None:
        raise DomainError("providers_required", "Execution requires --providers")
    providers = await asyncio.to_thread(read_providers, args.providers)
    if args.session is None and args.command not in {"run", "plan", "/plan"}:
        raise DomainError("session_required", "This command requires --session")
    if args.create and args.command not in {"run", "plan", "/plan", "hooks"}:
        raise DomainError("invalid_create_option", "Only run can create a session")
    session_id = args.session or uuid4().hex
    session = await open_agent_session(
        config,
        providers,
        session_id,
        create=args.create or args.session is None,
        consumers=(output,),
        refresh_models=not args.cached if args.command == "run" else False,
        startup_hooks=args.command != "hooks",
        connect_mcp=args.command != "hooks",
    )
    try:
        if args.command == "run":
            if not args.prompt.strip():
                raise DomainError("empty_prompt", "Prompt must not be empty")
            result = await session.run(Message(args.input_id or uuid4().hex, "user", args.prompt))
        elif args.command in {"plan", "/plan"}:
            planned = await session.enter_plan(args.task)
            if not isinstance(planned, RunResult):
                output.value(plain_data(planned))
                return 0
            result = planned
        elif args.command == "resume":
            if args.answers or args.answers_file:
                raise DomainError(
                    "run_expired", "Cross-process answers cannot resume old tools; start a new run"
                )
            output.value(await session.inspect())
            return 0
        elif args.command in {"compact", "/compact"}:
            output.value(plain_data(await session.compact()))
            return 0
        elif args.command == "branch":
            await session.branch(leaf_id(args.leaf))
            output.value(await session.inspect())
            return 0
        elif args.command == "revert":
            return await revert_command(args, session, output)
        elif args.command == "skill":
            if args.skill_command == "load":
                result = await session.load_skill(args.name, args.arguments)
            else:
                if args.skill_command == "reload":
                    value = await session.reload_skills()
                elif args.skill_command == "install":
                    value = await session.install_skill(args.url, args.ref, args.subpath)
                else:
                    value = await session.skill_search(args.query, args.limit)
                output.value(value)
                return 0
        elif args.command in {"memory", "/memory"}:
            if args.memory_command == "list":
                value = await session.memory_list()
            elif args.memory_command == "mode":
                await session.memory_mode(args.mode)
                value = {"mode": args.mode}
            elif args.memory_command == "remove":
                await session.memory_remove(args.memory_id)
                value = {"removed": args.memory_id}
            elif args.memory_command == "edit":
                value = {
                    "memory_id": await session.memory_edit(
                        args.memory_id, args.content, scope=args.scope, from_scope=args.from_scope
                    )
                }
            elif args.memory_command == "move":
                value = {
                    "memory_id": await session.memory_move(
                        args.memory_id, args.scope, from_scope=args.from_scope
                    ),
                    "scope": args.scope,
                }
            elif args.memory_command == "clear":
                await session.memory_clear()
                value = {"cleared": True}
            else:
                value = await session.memory_extract()
            output.value(value)
            return 0
        elif args.command == "hooks":
            await session.reconcile_hook(args.operation_id, args.action, args.text, args.is_error)
            output.value(
                {"operation_id": args.operation_id, "action": args.action, "reviewed": True}
            )
            return 0
        else:
            raise DomainError("unknown_command", "Unknown execution command")
        await session.publish_result(result, collect_background=True)
        return 0 if result.outcome == "completed" else 2 if result.outcome == "needs_input" else 1
    except asyncio.CancelledError:
        state = await session.inspect()
        run_id = state["pending_run_id"]
        if run_id is None:
            for entry in reversed(state["snapshot"]["entries"]):
                if entry["data"].get("kind") == "run_end":
                    run_id = entry["run_id"]
                    break
        if run_id is not None:
            await session.publish_result(
                RunResult("aborted", "", (), run_id=run_id),
                collect_background=True,
            )
        raise
    finally:
        await session.aclose()


async def revert_command(args, session, output):
    if args.revert_command == "preview":
        plan = await session.preview_revert(leaf_id(args.target), args.mode)
        if args.plan_out:
            await asyncio.to_thread(write_plan, args.plan_out, plan)
        output.value(plain_data(plan))
        return 2 if plan.files is not None and plan.files.conflicts else 0
    if args.revert_command == "apply":
        plan = await asyncio.to_thread(read_plan, args.plan_file)
        record = await session.revert(plan)
    else:
        record = await session.recover_revert(args.transaction_id, compensate=args.compensate)
    output.value(
        {
            "restore": plain_data(record) if record is not None else None,
            "session": await session.inspect(),
        }
    )
    return 0


async def view_command(args, config, output):
    if args.command in {"session", "/session"}:
        manager = build_session_service(config)
        if args.session_command == "list":
            output.value({"sessions": await manager.list_sessions()})
            return 0
        if args.session_command == "name":
            output.value(await manager.rename(args.session_id, args.title))
            return 0
        session_id = await manager.resolve(args.session_id)
    else:
        session_id = args.session
    view = await open_session_view(config, session_id)
    try:
        if args.command in {"usage", "/usage"}:
            report = await view.usage(args.run)
            if args.json or args.output_format != "text":
                output.value(report)
            else:
                output.usage(report)
        else:
            state = await view.inspect()
            if args.session_command == "fork":
                target = state["snapshot"]["leaf_id"] if args.leaf is None else leaf_id(args.leaf)
                output.value(await manager.fork(session_id, target, args.target_id))
            elif args.session_command == "tree":
                output.value(state["snapshot"])
            else:
                output.value(state)
    finally:
        await view.aclose()
    return 0
