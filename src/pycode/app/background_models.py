"""Owned one-request background model lifetimes; construction remains injected."""

from dataclasses import replace

from pycode.core.context_budget import InputBudget
from pycode.workflows.summary import SummaryWorkflow


class ConfiguredSummaryGenerator:
    def __init__(self, provider, model_factory, records_factory):
        self.provider = replace(
            provider, supports_tools=False, max_output_tokens=min(provider.max_output_tokens, 4096)
        )
        self.model_factory = model_factory
        self.records_factory = records_factory

    async def reconcile_lost(self, source):
        records = self.records_factory()
        try:
            await records.interrupt_unsettled(source.session_id, source.job_id)
        finally:
            await records.aclose()

    async def generate(self, source):
        owner = self.model_factory(self.provider, source.session_id, source.job_id, "summary")
        try:
            return await SummaryWorkflow(owner.model).run(source, owner.requests)
        finally:
            await owner.aclose()


class ConfiguredMemoryUpdater:
    """Every recall/update owns its model observer, permit and staging view."""

    def __init__(self, provider, model_factory, store_factory):
        self.store_factory = store_factory
        output_limit = 8192 if provider.thinking.get("type") in {"enabled", "adaptive"} else 4096
        self.provider = replace(
            provider, max_output_tokens=min(provider.max_output_tokens, output_limit)
        )
        self.model_factory = model_factory

    def facade(self, owner, prefix):
        from pycode.workflows.metered_model import MeteredChatModel

        budget = InputBudget.create(self.provider.context_window, self.provider.max_output_tokens)
        return MeteredChatModel(
            model=owner.model,
            requests=owner.requests,
            logical_prefix=prefix,
            input_limit=min(16000, budget.input_limit),
            max_calls=24,
            supports_forced_tool_choice=not (
                self.provider.implementation == "deepseek"
                and self.provider.thinking.get("type") == "enabled"
            ),
        )

    async def update(
        self,
        history,
        store,
        scopes,
        namespace,
        snapshot,
        run_id,
        *,
        output_reader=None,
        entry_ids=None,
        ranges=None,
        batch_id=None,
        progress=None,
        before_save=None,
        proposals=None,
        observed_revision=None,
        expected_targets=None,
        output_positions=None,
        retry_proposals=None,
        retry_reason=None,
        evaluations=None,
        evaluation_checkpoint=None,
    ):
        from pycode.core.hashing import data_hash
        from pycode.core.memory_evidence import MemoryEvidencePolicy, MemoryWritePermit
        from pycode.services.memory_commit import MemoryCommitService
        from pycode.services.memory_evidence import capture_memory_evidence
        from pycode.services.memory_retry import retry_plan
        from pycode.workflows.memory_evidence import MemoryEvidenceReviewer
        from pycode.workflows.memory_feedback import MemoryBehaviorStage, MemoryFeedbackDetector
        from pycode.workflows.memory_rule_evaluation import MemoryRuleEvaluator
        from pycode.workflows.memory_rules import MemoryRuleOptimizer
        from pycode.workflows.memory_update import MemoryUpdateWorkflow

        evidence = await capture_memory_evidence(
            snapshot,
            output_reader=output_reader,
            entry_ids=entry_ids,
            ranges=ranges,
            output_positions=output_positions,
        )
        policy = MemoryEvidencePolicy(evidence, scopes)
        if namespace not in scopes:
            from pycode.core.errors import ToolError

            raise ToolError("memory_scope_denied", "Memory update scope is outside this task")
        owner = self.model_factory(
            self.provider, snapshot.header.session_id, run_id, "memory_extract"
        )
        view = None
        try:
            model = self.facade(owner, "memory_update")
            permit = MemoryWritePermit()
            bound_store = self.store_factory(
                store.roots,
                model.model_copy(update={"logical_prefix": "memory_directory"}),
                permit,
            )
            view = await bound_store.open_update()
            retry = (
                retry_plan(evidence, view.read(namespace), retry_proposals, batch_id)
                if retry_proposals is not None
                else None
            )
            if (
                proposals is not None
                and expected_targets is None
                and view.read(namespace)["revision"] != observed_revision
            ):
                from pycode.core.errors import StorageError

                raise StorageError(
                    "memory_revision_conflict", "Staged proposals need fresh targets"
                )
            reviewer = MemoryEvidenceReviewer(
                model.model_copy(update={"logical_prefix": "memory_evidence"}),
                current_user_text=evidence.current_user_text,
            )
            commit = MemoryCommitService(history, policy, permit, reviewer)
            evaluator = MemoryRuleEvaluator(
                model.model_copy(update={"logical_prefix": "memory_rule_eval"}),
                policy,
                run_id=run_id,
                checkpoint=evaluation_checkpoint,
                evaluations=evaluations if proposals is not None else None,
                requests_lookup=lambda: owner.records.list_requests(snapshot.header.session_id),
                model_signature=data_hash(self.provider),
            )
            reviewer.evaluation_context = evaluator.review_context
            behavior = MemoryBehaviorStage(
                MemoryFeedbackDetector(
                    model.model_copy(update={"logical_prefix": "memory_feedback"}), policy
                ),
                MemoryRuleOptimizer(
                    model.model_copy(update={"logical_prefix": "memory_rule"}), policy
                ),
                policy,
                evaluator=evaluator,
            )
            return await MemoryUpdateWorkflow(
                model.model_copy(update={"logical_prefix": "memory_manager"}),
                view,
                namespace,
                scopes[namespace],
                evidence,
                commit,
                before_save=before_save,
                proposals=proposals,
                expected_targets=expected_targets,
                retry_plan=retry,
                retry_reason=retry_reason,
                behavior=behavior,
            ).run(batch_id=batch_id, progress=progress)
        finally:
            if view is not None:
                view.discard()
            await owner.aclose()

    async def recall(self, store, scopes, session_id, run_id, query, *, limit=5):
        from pycode.core.errors import ToolError
        from pycode.core.memory import snapshot_record
        from pycode.core.memory_evidence import MemoryWritePermit

        if set(scopes) != set(store.roots) or any(
            scope not in {"user", "project"} for scope in scopes.values()
        ):
            raise ToolError("memory_scope_denied", "Recall scope bindings must match the Store")
        owner = self.model_factory(self.provider, session_id, run_id, "memory_select")
        try:
            bound_store = self.store_factory(
                store.roots, self.facade(owner, "memory_recall"), MemoryWritePermit()
            )
            items = await bound_store.asearch((), query=query, limit=limit)
            return tuple(
                snapshot_record(
                    item.namespace,
                    item.key,
                    {
                        "value": item.value,
                        "created_at": item.created_at.isoformat(),
                        "updated_at": item.updated_at.isoformat(),
                    },
                    scopes[item.namespace],
                )
                for item in items
            )
        finally:
            await owner.aclose()

    async def active_operation(
        self,
        history,
        store,
        scopes,
        namespace,
        snapshot,
        context,
        arguments,
        *,
        manage,
        name,
        entry_ids=(),
        output_reader=None,
        arguments_sha256=None,
    ):
        from pycode.core.hashing import data_hash
        from pycode.core.memory import snapshot_record
        from pycode.core.memory_evidence import MemoryEvidencePolicy, MemoryWritePermit
        from pycode.core.memory_tool_receipt import receipt_id, receipt_metadata
        from pycode.core.messages import ToolResult
        from pycode.core.values import json_dumps
        from pycode.services.memory_commit import MemoryCommitService
        from pycode.services.memory_evidence import capture_memory_evidence
        from pycode.workflows.memory_evidence import MemoryEvidenceReviewer
        from pycode.workflows.memory_tools import official_memory_tool

        owner = self.model_factory(
            self.provider,
            snapshot.header.session_id,
            context.run_id + ":memory:" + context.operation_id,
            "memory_extract" if manage else "memory_select",
        )
        view = None
        try:
            model = self.facade(owner, "active_memory")
            permit = MemoryWritePermit()
            bound_store = self.store_factory(
                store.roots,
                model.model_copy(update={"logical_prefix": "active_memory_directory"}),
                permit,
            )
            if not manage:
                native = official_memory_tool(namespace, bound_store, manage=False, name=name)
                response = await native.ainvoke(
                    {
                        "type": "tool_call",
                        "id": context.call_id,
                        "name": name,
                        "args": arguments,
                    }
                )
                records = []
                for item in response.artifact or ():
                    record = {
                        "value": item.value,
                        "created_at": item.created_at.isoformat(),
                        "updated_at": item.updated_at.isoformat(),
                    }
                    records.append(
                        snapshot_record(item.namespace, item.key, record, scopes[item.namespace])
                    )
                from pycode.core.values import plain_data

                projected = plain_data(records)
                return ToolResult(
                    "",
                    "",
                    json_dumps(projected, ensure_ascii=False),
                    details={"memory_records": projected, "memory_namespace": list(namespace)},
                )
            evidence = await capture_memory_evidence(
                snapshot, entry_ids=entry_ids, output_reader=output_reader
            )
            policy = MemoryEvidencePolicy(evidence, scopes)
            view = await bound_store.open_update()
            native = official_memory_tool(namespace, view, manage=True, name=name)
            try:
                await native.ainvoke(arguments)
            except ValueError as error:
                from pycode.core.errors import ToolError

                raise ToolError(
                    "invalid_memory_arguments", "Memory operation arguments are invalid"
                ) from error
            reviewer = MemoryEvidenceReviewer(
                model.model_copy(update={"logical_prefix": "active_memory_evidence"}),
                current_user_text=evidence.current_user_text,
            )
            commit = MemoryCommitService(history, policy, permit, reviewer)
            delete_sources = [
                {"session_id": text.session_id, "entry_id": text.entry_id, "quote": text.text}
                for text in evidence.texts
                if text.role == "user" and text.text.strip() and len(text.text) <= 16000
            ]
            prepared = view.prepare()
            result = await commit.commit(
                view,
                namespace,
                delete_sources={
                    key: delete_sources
                    for key, value in prepared.changes[namespace].items()
                    if value is None
                },
                tool_receipt=(
                    receipt_id(namespace, context, name),
                    receipt_metadata(
                        context, name, arguments_sha256 or data_hash(arguments), evidence
                    ),
                ),
            )
            if not result.can_advance:
                from pycode.core.errors import ToolError

                reason = (
                    result.rejected_groups[0]["reason"]
                    if result.rejected_groups
                    else "memory_update_rejected"
                )
                raise ToolError(reason, "Memory operation was rejected; it was not saved")
            saved = await bound_store.tool_receipt_snapshot(
                namespace, receipt_id(namespace, context, name)
            )
            if saved is None:
                from pycode.core.errors import StorageError

                raise StorageError(
                    "memory_tool_snapshot_missing", "Committed tool receipt is missing"
                )
            receipt, saved_records = saved
            saved_ids = list(receipt["record_versions"])
            records, deleted = [], []
            for key in saved_ids:
                if key in saved_records:
                    records.append(
                        snapshot_record(namespace, key, saved_records[key], scopes[namespace])
                    )
                else:
                    deleted.append(key)
            from pycode.core.values import plain_data

            return ToolResult(
                "",
                "",
                "Saved memory operation: " + ", ".join(saved_ids),
                details={
                    "memory_namespace": list(namespace),
                    "memory_ids": saved_ids,
                    "memory_records": plain_data(records),
                    "memory_deleted": deleted,
                },
            )
        finally:
            if view is not None:
                view.discard()
            await owner.aclose()
