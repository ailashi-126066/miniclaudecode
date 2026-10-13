"""JSON schema boundary for static, Skill and MCP tool parameters."""

from typing import Any

from jsonschema import Draft202012Validator
from jsonschema.exceptions import SchemaError, ValidationError
from referencing.exceptions import Unresolvable

from pycode.core.errors import ToolError


class JsonSchemaValidator:
    def check_schema(self, schema: dict[str, Any]) -> None:
        try:
            Draft202012Validator.check_schema(schema)
        except SchemaError as error:
            raise ToolError("invalid_tool_schema", error.message) from error
        pending = [schema]
        while pending:
            item = pending.pop()
            if isinstance(item, dict):
                for key in ("$ref", "$dynamicRef"):
                    if key in item and not item[key].startswith("#"):
                        raise ToolError(
                            "invalid_tool_schema", "External schema references are unavailable"
                        )
                pending.extend(item.values())
            elif isinstance(item, list):
                pending.extend(item)

    def validate(self, schema: dict[str, Any], arguments: dict[str, Any]) -> None:
        try:
            Draft202012Validator(schema).validate(arguments)
        except ValidationError as error:
            location = ".".join(str(part) for part in error.absolute_path)
            raise ToolError(
                "invalid_arguments", f"{location or 'arguments'}: {error.message}"
            ) from error
        except (Unresolvable, RecursionError) as error:
            raise ToolError("invalid_tool_schema", "Cannot resolve tool input schema") from error
