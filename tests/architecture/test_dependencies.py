import pytest
from scripts.check_architecture import check_dependencies, dependency_errors


def test_project_dependency_direction():
    assert check_dependencies() == []


@pytest.mark.parametrize(
    ("module", "source"),
    [
        ("pycode.core.state", "from pycode.services.agent import Agent"),
        ("pycode.services.session", "from pycode.adapters.storage import Store"),
        ("pycode.workflows.agent", "from pycode.adapters.models import Model"),
        ("pycode.adapters.ui.app", "from pycode.services.session import AgentSession"),
        ("pycode.ports.session", "from langchain_core.messages import AIMessage"),
        ("pycode.services.context", "import langgraph"),
        ("pycode.core.state", "import pathlib"),
        ("pycode.core.state", "open('history.jsonl')"),
        ("pycode.app.cli", "from pycode.adapters.models import Model"),
        ("pycode.services.session", "from ..adapters.storage import Store"),
        ("pycode.core.state", "from .. import services"),
        ("pycode.app.container", "from evals.coding import runner"),
        ("pycode.app.cli", "import evals"),
        ("pycode.__main__", "from evals.__main__ import main"),
        ("pycode", "import scripts.check_architecture"),
        ("pycode.adapters.tools.bash", "from archive.scripts import run_check"),
        ("evals.coding.runner", "from archive.scripts.record_verification import manifest"),
        ("evals.scripts.run_output_quality", "import archive"),
    ],
)
def test_prohibited_dependencies_are_detected(module, source):
    assert dependency_errors(source, module)


@pytest.mark.parametrize(
    ("module", "source"),
    [
        ("pycode.core.messages", "from dataclasses import dataclass"),
        ("pycode.services.session", "from ..ports import session"),
        ("pycode.adapters.models.model", "from ...core.messages import Message"),
        ("pycode.workflows.agent", "from langchain.agents import create_agent"),
        ("pycode.app.container", "from pycode.adapters.storage import Store"),
        ("evals.coding.runner", "from pycode.app.container import build_runtime"),
        ("evals.quality.langfuse", "from pycode.adapters.models import Model"),
        ("evals.__main__", "from pycode.core.messages import Message"),
        ("evals.scripts.compare_eval_runs", "from evals.coding import provenance"),
        ("evals.coding", "from .workspaces import prepare"),
        ("scripts.verify_release", "from pycode.app.cli import main"),
        ("pycode.app.container", "from pycode.adapters.tools.bash import Bash"),
    ],
)
def test_allowed_dependencies(module, source):
    assert dependency_errors(source, module) == []


def test_relative_import_from_package():
    assert dependency_errors("from ..adapters import storage", "pycode.services", True)


def test_project_scan_covers_evals_and_skips_generated_data(tmp_path):
    package = tmp_path / "src" / "pycode"
    package.mkdir(parents=True)
    (package / "__init__.py").write_text("", encoding="utf-8")
    (package / "__main__.py").write_text("import evals\n", encoding="utf-8")
    evals = tmp_path / "evals"
    (evals / "coding").mkdir(parents=True)
    (evals / "coding" / "runner.py").write_text(
        "from pycode.app.container import build_runtime\nimport archive.scripts\n",
        encoding="utf-8",
    )
    corpus = evals / "cache" / "polyglot"
    corpus.mkdir(parents=True)
    (corpus / "solution.py").write_text("import archive\n", encoding="utf-8")
    assert check_dependencies(tmp_path) == [
        "pycode.__main__:1: pycode cannot import evals",
        "evals.coding.runner:2: evals cannot import archive.scripts",
    ]
