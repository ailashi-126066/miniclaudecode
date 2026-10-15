"""Official single/multi prompt_memory branches only stage scoped procedural records."""

import re
from uuid import NAMESPACE_URL, uuid5

from langchain_core.exceptions import OutputParserException
from langmem import Prompt, create_multi_prompt_optimizer, create_prompt_optimizer
from pydantic import ValidationError

from pycode.core.errors import ModelError, ToolError
from pycode.core.hashing import data_hash
from pycode.core.memory_schema import validate_memory_value
from pycode.core.values import plain_data

RULE_INSTRUCTIONS = (
    "Update only this scoped behavior rule using supported lasting feedback. "
    "Preserve template variables and the rule's task scope. "
    "Do not change permissions, tools, system safety constraints or project files. "
    "Current explicit user instructions take precedence over historical rules."
)


class MemoryRuleOptimizer:
    def __init__(self, model, policy):
        self.model = model
        self.policy = policy

    async def optimize(self, view, namespace, feedback, *, batch_id):
        if feedback is None or not (
            feedback.decision.supported
            and feedback.decision.durable
            and feedback.decision.scope_valid
        ):
            return ()
        sources = plain_data(feedback.sources)
        probe = validate_memory_value(
            {
                "kind": "SemanticMemory",
                "content": {
                    "title": feedback.title,
                    "summary": feedback.summary,
                    "body": feedback.text,
                    "category": "correction_feedback",
                    "sources": sources,
                },
            }
        )
        self.policy.validate(namespace, "feedback", probe, None)
        related = await view.asearch(
            namespace, query=feedback.text, filter={"kind": "ProceduralMemory"}, limit=8
        )
        mapping = {}
        for item in related:
            if item.namespace != namespace or item.value["kind"] != "ProceduralMemory":
                raise ToolError(
                    "memory_prompt_mapping_invalid", "Rule lookup returned a wrong scope or kind"
                )
            name = "rule-" + data_hash([namespace, item.key])
            if name in mapping:
                raise ToolError(
                    "memory_prompt_mapping_invalid", "Rule lookup returned a duplicate identity"
                )
            mapping[name] = (item.key, plain_data(item.value))
        if not mapping:
            key = uuid5(
                NAMESPACE_URL,
                data_hash({"scope": namespace, "batch": batch_id, "sources": sources}),
            ).hex
            value = validate_memory_value(
                {
                    "kind": "ProceduralMemory",
                    "content": {
                        "title": feedback.title,
                        "summary": feedback.summary,
                        "body": feedback.text,
                        "applicability": list(feedback.applicability),
                        "sources": sources,
                        "origin": "user_explicit" if feedback.explicit else "model_evaluated",
                    },
                }
            )
            mapping["rule-" + data_hash([namespace, key])] = (key, value)
        prompts = [
            Prompt(
                name=name,
                prompt=value["content"]["body"],
                when_to_update="Only lasting feedback relevant to: "
                + "; ".join(value["content"]["applicability"]),
                update_instructions=RULE_INSTRUCTIONS + "\nPycode rule identity: " + name + "\n",
            )
            for name, (_, value) in mapping.items()
        ]
        names = set(mapping)
        unchanged = set()
        prior_validator = self.model.response_validator
        prior_transform = self.model.parsed_response_transform

        def honor_no_change(input, schema, result):
            if prior_transform is not None:
                result = prior_transform(input, schema, result)
            if getattr(schema, "__name__", None) != "GeneralResponse" or result["update_prompt"]:
                return result
            text = input.to_string() if hasattr(input, "to_string") else str(input)
            matched = [name for name in names if "\nPycode rule identity: " + name + "\n" in text]
            if len(matched) != 1:
                raise ToolError(
                    "memory_prompt_mapping_invalid", "Unchanged result has no unique input rule"
                )
            name = matched[0]
            unchanged.add(name)
            # This LangMem version returns new_prompt even when update_prompt is false.
            return {**result, "new_prompt": mapping[name][1]["content"]["body"]}

        def validate_classification(response):
            if prior_validator is not None:
                prior_validator(response)
            for call in response.tool_calls:
                if call["name"] != "Classify":
                    continue
                selected = call["args"].get("which")
                if (
                    not isinstance(selected, list)
                    or any(not isinstance(name, str) or name not in names for name in selected)
                    or len(selected) != len(set(selected))
                ):
                    raise ModelError(
                        "memory_prompt_mapping_invalid",
                        "Optimizer chose an unknown or duplicate rule",
                    )

        model = self.model.model_copy(
            update={
                "response_validator": validate_classification,
                "parsed_response_transform": honor_no_change,
            }
        )
        trajectory = feedback.text
        try:
            if len(prompts) == 1:
                optimizer = create_prompt_optimizer(model, kind="prompt_memory")
                text = await optimizer.ainvoke({"trajectories": trajectory, "prompt": prompts[0]})
                outputs = [{**prompts[0], "prompt": text}]
            else:
                optimizer = create_multi_prompt_optimizer(model, kind="prompt_memory")
                outputs = await optimizer.ainvoke({"trajectories": trajectory, "prompts": prompts})
        except (ValidationError, OutputParserException) as error:
            raise ToolError("memory_rule_invalid", "Rule optimizer output is invalid") from error
        if not isinstance(outputs, list):
            raise ToolError(
                "memory_prompt_mapping_invalid", "Optimizer returned no named rule list"
            )
        staged = []
        seen = set()
        for output in outputs:
            if (
                not isinstance(output, dict)
                or output.get("name") not in mapping
                or output["name"] in seen
            ):
                raise ToolError(
                    "memory_prompt_mapping_invalid",
                    "Optimizer returned an unknown or duplicate rule identity",
                )
            seen.add(output["name"])
            key, old = mapping[output["name"]]
            body = old["content"]["body"] if output["name"] in unchanged else output.get("prompt")
            if not isinstance(body, str) or not body.strip() or len(body) > 16000:
                raise ToolError(
                    "memory_rule_invalid", "Optimized rule must be bounded nonblank text"
                )
            required = set(re.findall(r"(?<!\{)\{([A-Za-z_]\w*)\}(?!\})", old["content"]["body"]))
            if any("{" + variable + "}" not in body for variable in required):
                raise ToolError(
                    "memory_rule_invalid", "Optimized rule lost a required template variable"
                )
            value = plain_data(old)
            value["content"]["body"] = body
            value["content"]["origin"] = "user_explicit" if feedback.explicit else "model_evaluated"
            for source in sources:
                if source not in value["content"]["sources"]:
                    value["content"]["sources"].append(source)
            value = validate_memory_value(value)
            staged.append((key, value))
        if seen != names:
            raise ToolError(
                "memory_prompt_mapping_invalid", "Optimizer omitted a named rule result"
            )
        # Validate every named result before any staging; final source review/commit is separate.
        for key, value in staged:
            await view.aput(namespace, key, value)
        return tuple(key for key, _ in staged)
