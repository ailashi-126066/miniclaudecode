"""Durable team inboxes, reservation acknowledgements, and bounded communication."""

import asyncio
from uuid import uuid4

from pycode.core.errors import StorageError, ToolError
from pycode.core.messages import Message
from pycode.core.team import ACTIVE_AGENT_STATES, bounded_text, team_message_text


class TeamInbox:
    def __init__(self, team):
        self.team = team
        self.records = {}
        self.sent = {}

    def pending(self, recipient, *, wake_only=False):
        return [
            value
            for value in self.records.values()
            if value["recipient"] == recipient
            and value["status"] == "pending"
            and (not wake_only or value.get("wake", True))
        ]

    def notification(self, agent, kind, text, *, wake=True):
        return {
            "id": uuid4().hex[:16],
            "sender": agent["agent_id"],
            "recipient": "lead",
            "kind": kind,
            "text": text[:4000],
            "status": "pending",
            "wake": wake,
        }

    async def send(self, sender, to, text, run_id):
        team = self.team
        text = bounded_text(text, "message", 4000)
        async with team.lock:
            team.require_open()
            if to == sender:
                raise ToolError("cannot_message_self", "Send to a different team member")
            key = (sender, run_id)
            if self.sent.get(key, 0) >= 30:
                raise ToolError("message_limit_reached", "At most 30 messages per run")
            targets = (
                [
                    "lead",
                    *[
                        key
                        for key, value in team.records.items()
                        if value["status"] in ACTIVE_AGENT_STATES and value["kind"] == "agent"
                    ],
                ]
                if to == "all"
                else [to]
            )
            targets = [target for target in targets if target != sender]
            for target in targets:
                if target != "lead":
                    agent = team.require_agent(target)
                    if (
                        (
                            not agent.get("session_created", False)
                            and agent["status"] not in ACTIVE_AGENT_STATES
                        )
                        or agent["status"] in {"cancelled", "merged", "merging"}
                        or agent["kind"] == "delegate"
                    ):
                        raise ToolError("agent_closed", "This agent cannot receive messages")
                ordinary = [
                    m
                    for m in self.records.values()
                    if m["recipient"] == target
                    and m["kind"] == "message"
                    and m["status"] in {"pending", "delivering"}
                ]
                if len(ordinary) >= 20:
                    raise ToolError("inbox_full", "Recipient has 20 pending messages")
            messages = [
                {
                    "id": uuid4().hex[:16],
                    "sender": sender,
                    "recipient": target,
                    "kind": "message",
                    "text": text,
                    "status": "pending",
                    "wake": True,
                }
                for target in targets
            ]
            await team.commit(messages=messages)
            self.sent[key] = self.sent.get(key, 0) + 1
            for target in targets:
                if target != "lead":
                    team.wake_eligible.setdefault(target, None)
        await team.emit("message", {"from": sender, "to": targets})
        if "lead" in targets:
            await team.emit("wake", {})
        await team.schedule_wakes()
        return {"message_ids": [m["id"] for m in messages], "delivered_to": targets}

    async def reserve(self, recipient, via, owner, *, wake_only=False):
        async with self.team.lock:
            pending = self.pending(recipient, wake_only=wake_only)
            reserved = [
                {**m, "status": "delivering", "delivery": {"via": via, "owner": owner}}
                for m in pending
            ]
            await self.team.commit(messages=reserved)
            return reserved

    async def acknowledge(self, ids):
        async with self.team.lock:
            await self.team.commit(
                messages=[
                    {**self.records[key], "status": "delivered"}
                    for key in ids
                    if key in self.records and self.records[key]["status"] == "delivering"
                ]
            )

    async def release(self, ids):
        async with self.team.lock:
            await self.team.commit(
                messages=[
                    {**self.records[key], "status": "pending", "delivery": None}
                    for key in ids
                    if key in self.records and self.records[key]["status"] == "delivering"
                ]
            )

    async def flush(self, recipient, context):
        if context.durable_inputs is None:
            return
        messages = await self.reserve(recipient, "steer", context.run_id)
        remaining = [m["id"] for m in messages]
        try:
            for item in messages:
                input_id = "team-msg-" + item["id"]
                if not await self.team.input_accepted(recipient, input_id):
                    await context.durable_inputs.submit(
                        Message(
                            input_id,
                            "user",
                            team_message_text(item),
                            provider_metadata={"team_message": item["id"]},
                        ),
                        "steer",
                        context.run_id,
                    )
                await self.acknowledge([item["id"]])
                remaining.remove(item["id"])
        except StorageError as error:
            if error.code not in {"input_admission_closed", "no_pending_run"}:
                raise
        finally:
            if remaining:
                await self.release(remaining)

    async def wait(self, ids=None, timeout=120, *, operation_id=None):  # noqa: ASYNC109
        team = self.team
        if (
            isinstance(timeout, bool)
            or not isinstance(timeout, (float, int))
            or not 1 <= timeout <= 600
        ):
            raise ToolError("invalid_timeout", "Timeout must be 1–600 seconds")
        async with team.lock:
            targets = (
                list(ids)
                if ids is not None
                else [key for key, value in team.records.items() if value["kind"] == "agent"]
            )
            for identifier in targets:
                team.require_agent(identifier)
        reason = "timeout"
        try:
            async with asyncio.timeout(timeout):
                while True:
                    async with team.lock:
                        team.changed.clear()
                        if team.degraded:
                            raise ToolError(
                                "team_storage_error", "Team persistence failed; reopen the session"
                            )
                        if self.pending("lead"):
                            reason = "message"
                            break
                        if not any(
                            team.records[key]["status"] in {"starting", "running"}
                            for key in targets
                        ):
                            reason = "settled" if targets else "idle"
                            break
                    await team.changed.wait()
        except TimeoutError:
            pass
        owner = operation_id or uuid4().hex
        messages = await self.reserve("lead", "wait", owner)
        result = {"reason": reason, "messages": messages, **team.agents_view()}
        if operation_id is None:
            await self.acknowledge([m["id"] for m in messages])
        return result

    async def settle_tool(self, operation_id, *, accepted, message_ids=()):
        ids = [
            key
            for key, value in self.records.items()
            if value["status"] == "delivering"
            and value.get("delivery", {}).get("via") == "wait"
            and value["delivery"]["owner"] == operation_id
        ]
        delivered = [identifier for identifier in ids if accepted and identifier in message_ids]
        await self.acknowledge(delivered)
        await self.release([identifier for identifier in ids if identifier not in delivered])
