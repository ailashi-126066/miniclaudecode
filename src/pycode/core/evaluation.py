"""Fixed coding task definitions and honest outcome classification."""

import re
from dataclasses import dataclass


@dataclass(frozen=True)
class EvaluationTask:
    task_id: str
    source_path: str
    solution_files: tuple[str, ...]
    test_files: tuple[str, ...]
    files_sha256: dict[str, str]
    test_command: tuple[str, ...]
    timeout_seconds: int = 60
    smoke: bool = False


def evaluation_outcome(
    agent_outcome,
    test_exit_code,
    *,
    tampered=False,
    timeout=False,
    changed=True,
    verified=True,
    final_text="",
    failure_codes=(),
):
    if tampered:
        return "forbidden_change"
    if timeout:
        return "agent_timeout"
    if agent_outcome != "completed":
        return "agent_error"
    if not changed:
        return "no_patch"
    if "repeated_tool_failure" in failure_codes:
        return "repeated_tool_failure"
    claimed = claims_tests_passed(final_text)
    if claimed and test_exit_code != 0:
        return "claim_mismatch"
    if not verified:
        return "no_verification"
    if test_exit_code == 0:
        return "passed"
    return "tests_failed"


def claims_tests_passed(text):
    for clause in re.split(r"[.!?\n;]|,\s+|\bbut\b|但是", text, flags=re.IGNORECASE):
        for match in re.finditer(
            r"\btests?\s+(?:all\s+)?(?:passed|pass|successful)\b|测试(?:全部)?通过",
            clause,
            re.IGNORECASE,
        ):
            prefix = clause[: match.start()]
            if not re.search(
                r"\b(?:no|not|never|cannot|don['’]t|didn['’]t|haven['’]t)"
                r"(?:\s+(?:actual|actually|yet|all|claim|claimed|that|confirm|say)){0,4}\s*$"
                r"|(?:未|没有|不能|无法)(?:声明|声称)?\s*$",
                prefix,
                re.IGNORECASE,
            ):
                return True
    return False
