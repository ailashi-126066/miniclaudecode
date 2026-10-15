"""Reconstruct the system prompt independently of optional feature modules."""

from pycode.context.domain.projection import ProjectionContribution
from pycode.kernel import Message, ProjectedEntry, clone_value


class SystemPromptProjection:
    name = "system_prompt"
    checkpoint_keys = frozenset({"prompt_snapshot", "system_prompt"})

    def contribute(self, snapshot, path, entries, checkpoint):
        prompt = None
        for entry in path:
            if entry.type == "system_prompt":
                prompt = entry.data
            elif entry.type == "compaction":
                prompt = entry.data.get("checkpoint", {}).get("prompt_snapshot", prompt)
        if prompt is None:
            return ProjectionContribution()
        source_id = snapshot.leaf_id
        for entry in reversed(path):
            if entry.type in {"system_prompt", "compaction"}:
                source_id = entry.id
                break
        assert source_id is not None
        return ProjectionContribution(
            (
                ProjectedEntry(
                    source_id,
                    (
                        Message(
                            "prompt:" + prompt["sha256"],
                            "system",
                            prompt["text"],
                            provider_metadata={"source_type": "system_prompt"},
                        ),
                    ),
                ),
            ),
            {"prompt_snapshot": clone_value(prompt), "system_prompt": prompt["text"]},
        )
