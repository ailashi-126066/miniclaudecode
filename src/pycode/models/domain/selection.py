"""Choose a tool-selection model without owning provider construction or context I/O."""

from collections.abc import Callable
from dataclasses import replace

from pycode.kernel import ModelError
from pycode.models.domain.models import ProviderConfig


def selector_provider(
    provider: ProviderConfig, resolver: Callable[[str, str], ProviderConfig] | None
) -> ProviderConfig:
    selected = provider
    reference = provider.tool_selector.model
    if reference is not None:
        if resolver is None:
            raise ModelError("selector_model_unavailable", "No configured model resolver")
        selected = resolver(reference.provider, reference.model_id)
    if not selected.supports_tools:
        raise ModelError("selector_model_incompatible", "Selection model must support schema tools")
    return replace(
        selected,
        thinking={},
        reasoning_effort=None,
        max_output_tokens=min(selected.max_output_tokens, provider.tool_selector.output_limit),
    )
