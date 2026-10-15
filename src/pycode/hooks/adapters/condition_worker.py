# Source: author-owned hooks/conditions.py; SHA-256:
# d11485198df8f4ebf76a009c4b13eaf51883ae0a1bbc14f734779d684817f870
# Adaptation: potentially expensive regex evaluation runs in an owned, timed child.
"""Evaluate the source condition language without executing Python expressions."""

import fnmatch
import json
import re
import sys


def matches(condition, fields):
    value = fields.get(condition["field"], "")
    expected, operator = condition["value"], condition["operator"]
    if operator == "==":
        return value == expected
    if operator == "!=":
        return value != expected
    if operator == "~=":
        return fnmatch.fnmatch(value, expected)
    if operator == "=~":
        if expected.startswith("/") and expected.endswith("/"):
            expected = expected[1:-1]
        try:
            return bool(re.search(expected, value))
        except re.error:
            return False
    return False


def main():
    request = json.load(sys.stdin)
    group = request["condition"]
    results = [matches(item, request["fields"]) for item in group["conditions"]]
    print("true" if (all(results) if group["logic"] == "and" else any(results)) else "false")


if __name__ == "__main__":
    main()
