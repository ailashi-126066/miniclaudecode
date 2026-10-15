"""Static enforcement of module dependency direction and product branding."""

import ast
import os
import re
import tomllib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LAYERS = {"core", "ports", "services", "workflows", "adapters", "app"}
ALLOWED = {
    "core": {"core"},
    "ports": {"core", "ports"},
    "services": {"core", "ports", "services"},
    "workflows": {"core", "ports", "services", "workflows"},
    "adapters": {"core", "ports", "adapters"},
    "app": LAYERS,
}
CORE_IO = {
    "os",
    "pathlib",
    "subprocess",
    "socket",
    "urllib",
    "httpx",
    "requests",
    "sqlite3",
    "shutil",
    "tempfile",
    "aiofiles",
}
FRAMEWORKS = {
    "langchain",
    "langchain_core",
    "langchain_openai",
    "langchain_deepseek",
    "langchain_anthropic",
    "langgraph",
    "langmem",
    "trustcall",
    "textual",
    "deepagents",
    "fastmcp",
}
# Top-level packages each first-party package must never import: the product stays
# independent of evaluation, developer scripts and archived evidence; evals may use pycode.
FORBIDDEN_IMPORTS = {
    "pycode": frozenset({"evals", "scripts", "archive"}),
    "evals": frozenset({"archive"}),
}
# Locally generated evaluation data (corpus checkouts, work trees, results); never scanned.
EVAL_GENERATED = {"cache", "work", "results"}
# Archived evaluation code whose runtime dependencies were removed; kept for reference only.
EVAL_ARCHIVED = {"memory_rules"}


def imported_modules(node: ast.AST, module: str, is_package: bool) -> list[str]:
    if isinstance(node, ast.Import):
        return [alias.name for alias in node.names]
    if not isinstance(node, ast.ImportFrom):
        return []
    base = node.module or ""
    if node.level:
        package = module.split(".") if is_package else module.split(".")[:-1]
        keep = len(package) - node.level + 1
        base = ".".join(package[:keep] + ([base] if base else []))
    return [base, *[f"{base}.{alias.name}" for alias in node.names]]


def dependency_errors(source: str, module: str, is_package: bool = False) -> list[str]:
    parts = module.split(".")
    top = parts[0]
    forbidden = FORBIDDEN_IMPORTS.get(top, frozenset())
    layer = parts[1] if top == "pycode" and len(parts) >= 2 and parts[1] in LAYERS else None
    if not forbidden and layer is None:
        return []
    errors = []
    for node in ast.walk(ast.parse(source)):
        dependencies = imported_modules(node, module, is_package)
        blocked = next((item for item in dependencies if item.split(".")[0] in forbidden), None)
        if blocked is not None:
            errors.append(f"{module}:{node.lineno}: {top} cannot import {blocked}")
        if layer is None:
            continue
        for dependency in dependencies:
            imported = dependency.split(".")
            if len(imported) >= 2 and imported[0] == "pycode" and imported[1] in LAYERS:
                if imported[1] not in ALLOWED[layer]:
                    errors.append(f"{module}:{node.lineno}: {layer} cannot import {dependency}")
                if (
                    layer == "app"
                    and imported[1] == "adapters"
                    and module != "pycode.app.container"
                ):
                    errors.append(f"{module}:{node.lineno}: concrete adapters belong in container")
            if layer == "core" and imported[0] in CORE_IO:
                errors.append(f"{module}:{node.lineno}: core cannot import I/O module {dependency}")
            if layer in {"core", "ports", "services"} and imported[0] in FRAMEWORKS:
                errors.append(f"{module}:{node.lineno}: framework type outside adapter/workflow")
        if layer == "core" and isinstance(node, ast.Call):
            if isinstance(node.func, ast.Name) and node.func.id in {"open", "input", "print"}:
                errors.append(f"{module}:{node.lineno}: core cannot perform I/O")
    return errors


def module_name(relative: Path) -> tuple[str, bool]:
    parts = list(relative.with_suffix("").parts)
    is_package = parts[-1] == "__init__"
    if is_package:
        parts.pop()
    return ".".join(parts), is_package


def check_dependencies(root: Path = ROOT) -> list[str]:
    errors = []
    source_root = root / "src"
    for path in sorted((source_root / "pycode").rglob("*.py")):
        module, is_package = module_name(path.relative_to(source_root))
        errors.extend(dependency_errors(path.read_text(encoding="utf-8"), module, is_package))
    # evals is a top-level package next to src/; its generated corpora and runs are not code.
    for path in sorted((root / "evals").rglob("*.py")):
        relative = path.relative_to(root)
        if any(
            part in EVAL_GENERATED | EVAL_ARCHIVED or part.startswith(".")
            for part in relative.parts[1:]
        ):
            continue
        module, is_package = module_name(relative)
        errors.extend(dependency_errors(path.read_text(encoding="utf-8"), module, is_package))
    return errors


def branding_excluded(relative: Path) -> bool:
    parts = relative.parts
    if parts[:1] == ("archive",):
        return True
    if parts[:1] == ("evals",) and any(part in EVAL_GENERATED for part in parts[1:]):
        return True
    if parts[:2] == ("tests", "evals") and any(part in EVAL_GENERATED for part in parts[2:]):
        return True
    return False


def check_branding(root: Path = ROOT) -> list[str]:
    obsolete = re.compile("mew" + "code", re.IGNORECASE)
    errors = []
    excluded = {
        ".venv",
        ".source-cache",
        ".git",
        ".worktrees",
        ".pytest_cache",
        ".ruff_cache",
        "__pycache__",
        "dist",
        "build",
        ".runtime-tests",
        ".t",
        ".fix-20261013",
        "audit-20261013",
        "dependency-artifacts",
    }
    extensions = {".py", ".md", ".toml", ".json", ".tcss", ".txt", ".yaml", ".yml"}
    paths = []
    for directory, names, files in os.walk(root):
        current = Path(directory)
        # Prune excluded trees instead of walking caches, worktrees and archived evidence.
        names[:] = [
            name
            for name in names
            if name not in excluded and not branding_excluded((current / name).relative_to(root))
        ]
        paths.extend(current / name for name in files)
    for path in sorted(paths):
        relative = path.relative_to(root)
        if branding_excluded(relative):
            continue
        # Source attribution keeps actual upstream directory names; licenses keep notices.
        if relative.as_posix() == "docs/source-map.md" or relative.parts[:2] == (
            "docs",
            "licenses",
        ):
            continue
        if path.suffix not in extensions or path.name.startswith("._"):
            continue
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if not obsolete.search(line):
                continue
            stripped = line.strip()
            if stripped.startswith("#") and ("Source:" in line or "来源" in line):
                continue
            errors.append(f"{relative.as_posix()}:{number}: obsolete product brand")
    return errors


def check_harness(root: Path = ROOT) -> list[str]:
    errors = []
    project = tomllib.loads((root / "pyproject.toml").read_text(encoding="utf-8"))["project"]
    dependencies = set(project["dependencies"])
    for locked in ("deepagents==0.7.23", "langchain[mcp]==1.4.4", "langgraph==1.2.14"):
        if locked not in dependencies:
            errors.append("Final harness dependency is missing: " + locked)
    if project.get("scripts") != {"pycode": "pycode.app.cli:main"}:
        errors.append("Only the pycode console entry point may be registered")
    for dependency in dependencies:
        name = re.split(r"[<>=\[ ]", dependency)[0]
        if name in {"mcp", "langchain-mcp-adapters", "langgraph-checkpoint-sqlite"}:
            errors.append("Obsolete main-runtime direct dependency: " + name)
    for path in sorted((root / "src" / "pycode").rglob("*.py")):
        source = path.read_text(encoding="utf-8")
        relative = path.relative_to(root).as_posix()
        for node in ast.walk(ast.parse(source)):
            if isinstance(node, ast.ImportFrom):
                module = node.module or ""
                if (
                    module == "mcp"
                    or module.startswith("mcp.")
                    or module.startswith("langchain_mcp_adapters")
                ):
                    errors.append(f"{relative}:{node.lineno}: use LangChain MCPAdapter")
                if module == "langchain.agents" and any(
                    alias.name == "create_agent" for alias in node.names
                ):
                    errors.append(
                        f"{relative}:{node.lineno}: use create_deep_agent for Agent execution"
                    )
            if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute):
                if node.func.attr == "execute_batch":
                    errors.append(f"{relative}:{node.lineno}: obsolete tool batch scheduler")
        for obsolete in (
            "CREATE TABLE IF NOT EXISTS tool_operations",
            "CREATE TABLE IF NOT EXISTS runtime_events",
            "AsyncSqliteSaver",
        ):
            if obsolete in source:
                errors.append(f"{relative}: obsolete main-runtime persistence: {obsolete}")
    return errors


def main() -> int:
    errors = check_dependencies() + check_branding() + check_harness()
    for error in errors:
        print(error)
    if errors:
        return 1
    print("Architecture and branding checks passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
