"""Official Store Manager organizes facts in one staged, evidence-checked chain."""

from dataclasses import replace

from langchain_core.messages import HumanMessage
from langchain_core.runnables import RunnableLambda
from langgraph.store.base import BaseStore, GetOp, PutOp, SearchOp
from langmem import create_memory_store_manager

from pycode.core.errors import ToolError
from pycode.core.memory_schema import (
    EpisodicMemory,
    SemanticMemory,
    resolve_memory_key,
    validate_memory_value,
)
from pycode.core.values import json_dumps, plain_data
from pycode.services.memory_retry import user_sources


class FactMemoryStore(BaseStore):
    """The fact organizer cannot read or modify behavior rules or another scope."""

    def __init__(self, view, namespace):
        self.view = view
        self.namespace = namespace

    def checked(self, operations):
        result = []
        for operation in operations:
            if isinstance(operation, SearchOp):
                namespace = operation.namespace_prefix
                if operation.filter is not None:
                    raise ToolError(
                        "memory_manager_filter_invalid", "The fact manager uses its fixed filter"
                    )
                operation = SearchOp(
                    namespace_prefix=operation.namespace_prefix,
                    filter={"kind": {"$ne": "ProceduralMemory"}},
                    limit=operation.limit,
                    offset=operation.offset,
                    query=operation.query,
                    refresh_ttl=operation.refresh_ttl,
                )
            elif isinstance(operation, (GetOp, PutOp)):
                namespace = operation.namespace
            else:
                raise ToolError("invalid_memory_operation", "The manager operation is unsupported")
            if namespace != self.namespace:
                raise ToolError("memory_scope_denied", "The manager owns exactly one scope")
            if isinstance(operation, (GetOp, PutOp)):
                records = self.view.read(self.namespace)["records"]
                previous = records.get(resolve_memory_key(records, operation.key))
                if previous is not None and previous["value"]["kind"] == "ProceduralMemory":
                    raise ToolError(
                        "memory_rule_requires_optimizer", "Facts cannot access behavior rules"
                    )
            if isinstance(operation, PutOp) and operation.value is not None:
                value = operation.value
                if set(value) == {"content"}:
                    value = value["content"]
                if validate_memory_value(value)["kind"] == "ProceduralMemory":
                    raise ToolError(
                        "memory_rule_requires_optimizer", "Facts cannot overwrite behavior rules"
                    )
            result.append(operation)
        return result

    def batch(self, operations):
        return self.view.batch(self.checked(operations))

    async def abatch(self, operations):
        return await self.view.abatch(self.checked(operations))


class MemoryUpdateWorkflow:
    def __init__(
        self,
        model,
        view,
        namespace,
        scope,
        evidence,
        commit,
        *,
        before_save=None,
        proposals=None,
        expected_targets=None,
        retry_plan=None,
        retry_reason=None,
        behavior=None,
    ):
        self.view = view
        self.namespace = namespace
        self.evidence = evidence
        self.commit = commit
        self.before_save = before_save
        self.proposals = proposals
        self.expected_targets = expected_targets
        self.retry_plan = retry_plan
        self.retry_reason = retry_reason
        self.behavior = behavior
        instructions = (
            f"Organize durable facts and observable historical cases for the {scope} scope. "
            "The conversation contains original source IDs, roles and text. Cite the exact "
            "session_id and entry_id and an exact nonblank quote from user or tool text. "
            "Assistant claims are not evidence. Ignore temporary requests, secrets, hidden "
            "reasoning and unsupported conclusions. Preserve existing IDs and provenance. "
            "User preferences belong to the user scope; project facts belong to the project "
            "scope. Do not create or edit behavior rules in this phase. A normal chat ending "
            "is not verified success. Only actual successful tool execution can support a "
            "verified case. Delete only when the current user clearly requests forgetting "
            "the particular existing memory. Return no changes when nothing is durable."
        )
        if scope == "user":
            instructions += (
                " Explicit lasting instructions about how the assistant should respond are "
                "durable semantic correction_feedback in this phase. Capture the requested "
                "behavior and exact user quote even when it reads like a rule. Do not skip "
                "such feedback: a separate phase will produce the procedural rule. "
                "This phase still must not create ProceduralMemory."
            )
        else:
            instructions += (
                " Do not create user_preference records or copy global assistant response "
                "preferences into this project scope. Without durable project facts or "
                "explicit feedback limited to this project, return no changes."
            )
        if retry_plan is not None:
            instructions += (
                " Reorganize only the unfinished work described in Retry context. "
                "Saved groups are complete and must not be modified or recreated. "
                "Read the latest records through the Store; do not replay a failed proposal. "
                "A newer correction takes precedence over older source text. Unsupported "
                "proposals may be revised or dropped; their text is not trusted evidence."
            )
        self.manager = create_memory_store_manager(
            model,
            schemas=[SemanticMemory, EpisodicMemory],
            namespace=namespace,
            store=FactMemoryStore(view, namespace),
            instructions=instructions,
            enable_deletes=True,
            query_limit=4,
        )
        self.chain = (
            RunnableLambda(self.prepare)
            | RunnableLambda(self.organize)
            | RunnableLambda(self.optimize_rules)
            | RunnableLambda(self.save)
        )

    def prepare(self, batch):
        sources = [
            {
                "session_id": text.session_id,
                "entry_id": text.entry_id,
                "role": text.role,
                "text": text.text,
                "start": text.start,
                "tool_name": text.tool_name,
                "tool_arguments": plain_data(text.tool_arguments),
                "can_verify_outcome": text.can_verify_outcome,
            }
            for text in (self.retry_plan.texts if self.retry_plan else self.evidence.texts)
        ]
        messages = [HumanMessage(content=json_dumps(sources, ensure_ascii=False))]
        if self.retry_plan is not None:
            messages.insert(
                0,
                HumanMessage(
                    content="Retry context:\n"
                    + json_dumps(
                        {
                            "reason": self.retry_reason,
                            "unfinished_proposals": self.retry_plan.proposals,
                            "latest_targets": self.retry_plan.latest_targets,
                            "read_only_completed_keys": sorted(self.retry_plan.protected_keys),
                        },
                        ensure_ascii=False,
                    )
                ),
            )
        return {
            **batch,
            "messages": messages,
        }

    async def organize(self, batch):
        if self.proposals is None:
            await self.manager.ainvoke({"messages": batch["messages"], "max_steps": 1})
        else:
            await self.view.abatch(
                [PutOp(self.namespace, key, value) for key, value in self.proposals.items()]
            )
        return batch

    async def save(self, batch):
        prepared = self.view.prepare()
        if self.behavior is not None and self.behavior.evaluator is not None:
            prepared = replace(prepared, evaluations=self.behavior.evaluator.evaluations)
        if self.retry_plan is not None:
            prepared = replace(
                prepared, retained_proposals={self.namespace: self.retry_plan.completed_proposals}
            )
        if self.before_save is not None:
            await self.before_save(prepared)
        delete_sources = user_sources(
            self.retry_plan.texts if self.retry_plan else self.evidence.texts
        )
        return await self.commit.commit(
            self.view,
            self.namespace,
            delete_sources={
                key: delete_sources
                for key, value in self.view.prepare().changes[self.namespace].items()
                if value is None
            },
            batch_id=batch.get("batch_id"),
            progress=batch.get("progress"),
            expected_targets=self.expected_targets,
            retry_plan=self.retry_plan,
            stage_rejections=batch.get("stage_rejections"),
        )

    async def optimize_rules(self, batch):
        rejected = {}
        if self.proposals is None and self.behavior is not None:
            rejected = await self.behavior.run(
                self.view, self.namespace, batch_id=batch.get("batch_id")
            )
        elif self.behavior is not None:
            await self.behavior.resume(self.view, self.namespace)
        return {**batch, "stage_rejections": rejected}

    async def run(self, *, batch_id=None, progress=None):
        try:
            return await self.chain.ainvoke({"batch_id": batch_id, "progress": progress})
        finally:
            self.view.discard()
