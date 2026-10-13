"""Local Agent Protocol host for the framework's five Async Subagents tools.

Each thread owns one existing pycode session. This boundary owns invocation
tasks and cancellation, not an Agent loop or a second conversation store.
"""

import asyncio
import json
from contextlib import asynccontextmanager
from dataclasses import dataclass, field
from datetime import UTC, datetime
from uuid import uuid4

from starlette.applications import Starlette
from starlette.exceptions import HTTPException
from starlette.responses import JSONResponse
from starlette.routing import Route

from pycode.core.values import plain_data


def timestamp():
    return datetime.now(UTC).isoformat()


@dataclass
class ProtocolThread:
    thread_id: str
    created_at: str
    worker: object = None
    graph_id: str = ""
    lock: asyncio.Lock = field(default_factory=asyncio.Lock)


class LocalAgentProtocol:
    def __init__(self, worker_factory, *, concurrency=4):
        if isinstance(concurrency, bool) or not isinstance(concurrency, int) or concurrency < 1:
            raise ValueError("Worker concurrency must be a positive integer")
        self.concurrency = concurrency
        self.worker_factory = worker_factory
        self.threads = {}
        self.runs = {}
        self.running = {}
        self.start_lock = asyncio.Lock()
        self.closed = False

    def thread(self, identifier):
        thread = self.threads.get(identifier)
        if thread is None:
            raise HTTPException(404, "Unknown Worker thread")
        return thread

    def run(self, thread_id, run_id):
        self.thread(thread_id)
        run = self.runs.get(run_id)
        if run is None or run["thread_id"] != thread_id:
            raise HTTPException(404, "Unknown Worker run")
        return run

    async def create_thread(self, request):
        identifier = str(uuid4())
        thread = ProtocolThread(identifier, timestamp())
        self.threads[identifier] = thread
        return JSONResponse({"thread_id": identifier, "created_at": thread.created_at})

    async def create_run(self, request):
        thread = self.thread(request.path_params["thread_id"])
        body = await request.json()
        if not isinstance(body, dict) or not isinstance(body.get("input"), dict):
            raise HTTPException(422, "Expected an input object")
        graph_id = body.get("assistant_id")
        messages = body.get("input", {}).get("messages", [])
        if not isinstance(graph_id, str) or not graph_id:
            raise HTTPException(422, "assistant_id is required")
        if (
            not isinstance(messages, list)
            or len(messages) != 1
            or not isinstance(messages[0], dict)
            or messages[0].get("role") != "user"
        ):
            raise HTTPException(422, "Expected one user instruction")
        instruction = messages[0].get("content")
        if not isinstance(instruction, str) or not instruction.strip():
            raise HTTPException(422, "Instruction must be nonempty text")
        options = {}
        try:
            wrapped = json.loads(instruction)
        except ValueError:
            wrapped = None
        if isinstance(wrapped, dict) and set(wrapped) == {"pycode_task"}:
            task = wrapped["pycode_task"]
            if not isinstance(task, dict) or set(task) != {"instruction", "worktree"}:
                raise HTTPException(422, "Invalid explicit Worker task")
            options = task["worktree"]
            if not isinstance(options, dict) or set(options) != {"base", "include_uncommitted"}:
                raise HTTPException(422, "Invalid Worker start options")
            if (
                not isinstance(options["base"], str)
                or not options["base"]
                or options["base"].startswith("-")
                or not isinstance(options["include_uncommitted"], bool)
            ):
                raise HTTPException(422, "Invalid Worker start options")
            instruction = task["instruction"]
            if not isinstance(instruction, str) or not instruction.strip():
                raise HTTPException(422, "Worker instruction must be nonempty text")
        async with self.start_lock, thread.lock:
            if thread.worker is not None and (graph_id != thread.graph_id or options):
                raise HTTPException(409, "An existing Worker keeps its role and workspace")
            active = self.running.get(thread.thread_id)
            if active is not None and not active.done():
                if body.get("multitask_strategy") != "interrupt":
                    raise HTTPException(409, "Worker is running; use interrupt to update")
                active.cancel()
                await asyncio.gather(active, return_exceptions=True)
            running = 0
            for task in self.running.values():
                if not task.done():
                    running += 1
            if running >= self.concurrency:
                raise HTTPException(409, "Local Worker concurrency limit reached")
            if thread.worker is None:
                thread.worker = await self.worker_factory(thread.thread_id, graph_id, **options)
                thread.graph_id = graph_id
            elif graph_id != thread.graph_id:
                raise HTTPException(409, "A Worker thread cannot change its Agent role")
            run = {
                "run_id": str(uuid4()),
                "thread_id": thread.thread_id,
                "assistant_id": graph_id,
                "status": "pending",
                "created_at": timestamp(),
            }
            self.runs[run["run_id"]] = run
            self.running[thread.thread_id] = asyncio.create_task(
                self.execute(thread, run, instruction), name="pycode-worker:" + run["run_id"]
            )
        return JSONResponse(dict(run))

    async def execute(self, thread, run, instruction):
        run["status"] = "running"
        try:
            result = await thread.worker.run(instruction)
            run["status"] = "success" if result.outcome == "completed" else "error"
            if result.outcome != "completed":
                run["error"] = "Worker ended with " + result.outcome
                if result.outcome == "needs_input":
                    run["pending"] = plain_data(result.pending)
                    run["error"] = json.dumps(
                        {"code": "worker_needs_input", "pending": run["pending"]}
                    )
        except asyncio.CancelledError:
            run["status"] = "cancelled"
            raise
        except Exception as error:
            # Agent Protocol explicitly exposes failed runs to its caller.
            run["status"] = "error"
            run["error"] = str(error)
        finally:
            run["updated_at"] = timestamp()

    async def get_run(self, request):
        return JSONResponse(dict(self.run(**request.path_params)))

    async def get_thread(self, request):
        thread = self.thread(request.path_params["thread_id"])
        values = {}
        if thread.worker is not None:
            values = await thread.worker.values()
        return JSONResponse(
            {
                "thread_id": thread.thread_id,
                "created_at": thread.created_at,
                "values": values,
            }
        )

    async def cancel_run(self, request):
        run = self.run(**request.path_params)
        thread = self.thread(run["thread_id"])
        async with thread.lock:
            return await self.cancel_locked(run, thread)

    async def cancel_locked(self, run, thread):
        active = self.running.get(run["thread_id"])
        if run["status"] in {"pending", "running"} and active is not None:
            active.cancel()
            await asyncio.gather(active, return_exceptions=True)
            run["status"] = "cancelled"
        elif run.get("pending") and active is not None and active.done():
            current = None
            for candidate in self.runs.values():
                if candidate["thread_id"] == thread.thread_id:
                    current = candidate
            if current is run:
                await thread.worker.cancel()
                run["status"] = "cancelled"
                run.pop("pending", None)
                run.pop("error", None)
                run["updated_at"] = timestamp()
        return JSONResponse(dict(run))

    async def aclose(self):
        if self.closed:
            return
        self.closed = True
        for task in self.running.values():
            if not task.done():
                task.cancel()
        await asyncio.gather(*self.running.values(), return_exceptions=True)
        errors = []
        for thread in self.threads.values():
            if thread.worker is not None:
                try:
                    await thread.worker.aclose()
                except Exception as error:
                    errors.append(error)
        if errors:
            raise ExceptionGroup("Worker close failures", errors)

    def application(self):
        @asynccontextmanager
        async def lifespan(app):
            try:
                yield
            finally:
                await self.aclose()

        async def health(request):
            return JSONResponse({"ok": True})

        return Starlette(
            lifespan=lifespan,
            routes=[
                Route("/ok", health),
                Route("/threads", self.create_thread, methods=["POST"]),
                Route("/threads/{thread_id}", self.get_thread),
                Route("/threads/{thread_id}/runs", self.create_run, methods=["POST"]),
                Route("/threads/{thread_id}/runs/{run_id}", self.get_run),
                Route(
                    "/threads/{thread_id}/runs/{run_id}/cancel", self.cancel_run, methods=["POST"]
                ),
            ],
        )
