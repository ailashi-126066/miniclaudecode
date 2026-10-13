import pytest
from tools.check_architecture import check_dependencies, dependency_errors


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
    ],
)
def test_allowed_dependencies(module, source):
    assert dependency_errors(source, module) == []


def test_relative_import_from_package():
    assert dependency_errors("from ..adapters import storage", "pycode.services", True)
