# Source: local context/summary_cache.py, SHA-256:
# e291f27c5264240e6a535b4ec997e0958cec665f5be1e36f0f9f5f7b08621db0
# Adaptation: extracted existing phase-5 estimator for identical runtime/UI accounting.
"""One pure context estimator, with provenance rather than fabricated live counts."""

from pycode.kernel import (
    SessionTree,
    clone_value,
    data_hash,
    estimate_messages,
    estimate_text,
    json_dumps,
    plain_data,
)


class ContextMeter:
    def __init__(self, settings):
        self._settings = settings

    def checkpoint(self, projection):
        checkpoint = clone_value(projection.checkpoint)
        system = [
            message.text
            for message in projection.messages
            if message.role == "system"
            and message.provider_metadata.get("source_type")
            not in {"skill_catalog", "skill_load", "mcp_catalog"}
        ]
        checkpoint.update(
            system_prompt="\n\n".join(system) if system else None,
            tools=[
                plain_data(spec)
                for spec in sorted(self._settings.tools, key=lambda spec: spec.name)
            ],
            selection=plain_data(self._settings.selection),
        )
        return checkpoint

    def config_hash(self, projection):
        return data_hash(
            {"settings": plain_data(self._settings), "checkpoint": self.checkpoint(projection)}
        )

    def report(self, snapshot, projection):
        fingerprint = self.config_hash(projection)
        path = SessionTree(snapshot.index).path(snapshot.leaf_id)
        positions = {entry.id: index for index, entry in enumerate(path)}
        invalidating = max(
            (
                index
                for index, entry in enumerate(path)
                if entry.type
                in {
                    "context_edit",
                    "compaction",
                    "model_change",
                    "thinking_level_change",
                    "prompt_change",
                    "tool_set_change",
                    "skill_load",
                }
            ),
            default=-1,
        )
        by_message = {}
        for item in projection.entries:
            for message in item.messages:
                by_message[message.id] = item.entry_id
        tokens, source, anchor = None, "estimate", None
        for index in range(len(projection.messages) - 1, -1, -1):
            message = projection.messages[index]
            usage = message.usage
            if (
                message.role != "assistant"
                or usage is None
                or message.stop_reason in {"error", "aborted", "content_filter"}
            ):
                continue
            # The fingerprint covers the actual memory snapshot. Repeating an
            # unchanged recall must not discard authoritative provider usage.
            if message.provider_metadata.get("pycode_summary_config_hash") != fingerprint:
                continue
            if positions.get(by_message.get(message.id), -1) <= invalidating:
                continue
            reported = (
                usage.input_tokens + usage.output_tokens
                if usage.input_tokens is not None and usage.output_tokens is not None
                else usage.total_tokens
            )
            if reported is not None and reported > 0:
                tail = projection.messages[index + 1 :]
                tokens = reported + estimate_messages(tail)
                source = "provider_usage_and_estimated_tail" if tail else "provider_usage"
                anchor = usage.request_id
                break
        if tokens is None:
            tokens = estimate_messages(projection.messages) + estimate_text(
                json_dumps(self.checkpoint(projection)["tools"], sort_keys=True, ensure_ascii=False)
            )
            tokens += projection.checkpoint.get("harness_system_tokens", 0)
        budget = self._settings.budget
        return {
            "tokens": tokens,
            "window": budget.window,
            "input_limit": budget.input_limit,
            "output_reserve": budget.output_reserve,
            "summary_trigger": budget.cache_trigger,
            "fraction": tokens / budget.window,
            "source": source,
            "anchor_request_id": anchor,
        }

    def estimate(self, snapshot, projection):
        return self.report(snapshot, projection)["tokens"]
