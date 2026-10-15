# Source: https://github.com/anomalyco/opencode/blob/055d95bb7e278c94baf06235a52cac79dd13ba67/packages/opencode/src/session/compaction.ts
# Adaptation: whole entries, proportional budgets, >= threshold and append-only decisions.
"""Pure selection of old completed tool results, preserving recent input regions."""

from dataclasses import dataclass

from pycode.kernel import ProjectedContext, SessionSnapshot, estimate_text
from pycode.models import model_tool_text


@dataclass(frozen=True)
class EvictionBudget:
    input_limit: int
    protect_tokens: int | None = None
    minimum_tokens: int | None = None

    def limits(self) -> tuple[int, int]:
        if self.input_limit <= 0:
            raise ValueError("Input window must be positive")
        protect = (
            min(self.input_limit // 4, 40000)
            if self.protect_tokens is None
            else self.protect_tokens
        )
        minimum = (
            min(self.input_limit // 8, 20000)
            if self.minimum_tokens is None
            else self.minimum_tokens
        )
        if protect < 0 or minimum < 0:
            raise ValueError("Eviction budgets must not be negative")
        return protect, minimum


@dataclass(frozen=True)
class EvictionSelection:
    entry_ids: tuple[str, ...]
    tokens: int


def select_old_results(
    snapshot: SessionSnapshot,
    projection: ProjectedContext,
    budget: EvictionBudget,
    *,
    protected_entry_ids: tuple[str, ...] = (),
) -> EvictionSelection:
    protect, minimum = budget.limits()
    active_ids = {item.entry_id for item in projection.entries}
    prior = {
        entry.data["target_id"]
        for entry in snapshot.entries
        if entry.type == "context_edit"
        and entry.data.get("kind") == "tool_eviction"
        and entry.id in active_ids
    }
    total = 0
    recoverable = 0
    users = 0
    selected = []
    visible_results = {
        message.id for message in projection.messages if message.tool_result is not None
    }
    for contribution in reversed(projection.entries):
        for message in reversed(contribution.messages):
            if message.role == "user" and not message.provider_metadata.get("system_reminder"):
                users += 1
            if users < 2:
                continue
            if message.provider_metadata.get("source_type") == "compaction":
                return EvictionSelection(
                    tuple(selected) if recoverable >= minimum else (), recoverable
                )
            result = message.tool_result
            if result is None or message.id not in visible_results:
                continue
            if contribution.entry_id in protected_entry_ids:
                continue
            if contribution.entry_id in prior:
                return EvictionSelection(
                    tuple(selected) if recoverable >= minimum else (), recoverable
                )
            if result.name.lower() in {"skill", "loadskill"} or result.details.get(
                "protected_content"
            ):
                continue
            if result.details.get("status") in {"running", "pending", "unknown"}:
                continue
            tokens = estimate_text(model_tool_text(result))
            total += tokens
            if total <= protect:
                continue
            selected.append(contribution.entry_id)
            recoverable += tokens
    return EvictionSelection(tuple(selected) if recoverable >= minimum else (), recoverable)
