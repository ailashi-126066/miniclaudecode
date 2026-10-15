"""Coding evaluations use existing sessions and the exact configured model."""

import asyncio
from dataclasses import replace
from uuid import uuid4

from pycode.core.messages import Message
from pycode.core.summary_source import data_hash
from pycode.core.values import plain_data


class ConfiguredEvaluationSolver:
    def __init__(self, config, providers, session_opener, metadata_factory, trace_path):
        self.config = config
        self.providers = providers
        self.session_opener = session_opener
        self.metadata_factory = metadata_factory
        self.trace_path = trace_path

    async def solve(self, task, workspace):
        config = replace(
            self.config,
            workspace=workspace,
            user_data=workspace.parent / "state",
            memory_mode="off",
            project_trusted=False,
            allow_delegation=False,
            evaluation_files=task.solution_files,
            evaluation_tests=task.test_files,
            credential_workspace=self.config.credential_workspace or self.config.workspace,
        )
        identity = self.metadata_factory()
        session = None
        try:
            instructions = (workspace / ".docs/instructions.md").read_text(encoding="utf-8")
            prompt = (
                "Implement the exercise using only these solution files: "
                + ", ".join(task.solution_files)
                + ". Read them before editing. "
                "Do not modify tests or other files, install dependencies, commit, "
                "or use external tools. "
                "Run the existing tests with python -m pytest -q.\n\n" + instructions
            )
            failure = {}
            outcome, final_text = "agent_error", ""
            try:
                # Bound the actual solver, then collect settled receipts outside
                # that deadline. A timeout must retain the requests it incurred.
                async with asyncio.timeout(task.timeout_seconds * 3):
                    session = await self.session_opener(
                        config,
                        self.providers,
                        "evaluation",
                        create=True,
                        refresh_models=False,
                        startup_hooks=False,
                        connect_mcp=False,
                    )
                    result = await session.run(Message(uuid4().hex, "user", prompt))
                    outcome, final_text = result.outcome, result.final_text
            except TimeoutError:
                outcome = "timeout"
            except Exception as error:
                failure = {"error_type": type(error).__name__, "error": str(error)}
            if session is None:
                return {"outcome": outcome, "final_text": final_text, **identity, **failure}
            view = await session.inspect()
            entries = view["snapshot"]["entries"]
            failures = []
            tools = []
            edits = []
            verified_at = -1
            prompt_hash = None
            for position, entry in enumerate(entries):
                if entry["type"] == "system_prompt":
                    prompt_hash = entry["data"]["sha256"]
                message = entry.get("message")
                tool = message.get("tool_result") if message is not None else None
                if tool is None:
                    continue
                tools.append(
                    {
                        "name": tool["name"],
                        "operation_id": tool["operation_id"],
                        "is_error": tool["is_error"],
                        "error_code": tool["error_code"],
                        "exit_code": tool["exit_code"],
                    }
                )
                if tool["error_code"]:
                    failures.append(tool["error_code"])
                if not tool["is_error"] and tool["name"] in {"write_file", "edit_file"}:
                    edits.append(position)
                if tool["name"] == "Bash" and not tool["is_error"] and tool["exit_code"] == 0:
                    verified_at = position
            requests = []
            for record in await session.request_records():
                usage = plain_data(record.usage) if record.usage is not None else None
                if usage is not None:
                    usage.pop("raw", None)
                requests.append(
                    {
                        "request_id": record.request_id,
                        "status": record.status,
                        "purpose": record.purpose,
                        "attempt": record.attempt,
                        "started_at": record.started_at,
                        "settled_at": record.settled_at,
                        "error_code": record.error_code,
                        "usage": usage,
                    }
                )
            return {
                "outcome": outcome,
                "final_text": final_text,
                "session_id": "evaluation",
                "usage": await session.usage(),
                **identity,
                "provider": plain_data(self.providers[0]),
                "configuration_hash": data_hash(plain_data(self.providers[0])),
                "system_prompt_hash": prompt_hash,
                "trace_file": self.trace_path(config, "evaluation"),
                "tools": tools,
                "model_requests": requests,
                "failure_codes": failures,
                "verified": verified_at > max(edits, default=-1),
                **failure,
            }
        finally:
            if session is not None:
                await session.aclose()
