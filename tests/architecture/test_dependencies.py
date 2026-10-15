"""Architecture enforcement covers real imports, aliases, public origins and cycles."""

import pytest
from scripts.check_architecture import check_dependencies, dependency_errors


def test_project_dependency_direction():
    assert check_dependencies() == []


@pytest.mark.parametrize(
    ("module", "source"),
    [
        ("pycode.tools.domain.policy", "from pycode.tools.services.executor import Executor"),
        ("pycode.memory.ports.store", "from pycode.memory.services.memory import MemoryService"),
        ("pycode.memory.services.memory", "from pycode.memory.adapters.store import Store"),
        ("pycode.models.services.control", "from pycode.context import ContextProjector"),
        ("pycode.hooks.services.engine", "from pycode.tools import ToolExecutor"),
        ("pycode.session.services.agent", "from pycode.models.domain.models import ProviderConfig"),
        ("pycode.agent.adapters.agent", "from pycode.tools.adapters.settlement import Middleware"),
        ("pycode.app.cli", "from pycode.models.adapters.factory import ModelFactory"),
        ("pycode.agent.ports.runtime", "from langchain_core.messages import AIMessage"),
        ("pycode.context.services.summary", "import langgraph"),
        ("pycode.memory.domain.policy", "import pathlib as filesystem"),
        ("pycode.memory.domain.policy", "from builtins import open as reader\nreader('x')"),
        ("pycode.memory.domain.policy", "import builtins as b\nb.open('x')"),
        ("pycode.memory.domain.policy", "reader = open\nreader('x')"),
        ("pycode.tools.services.executor", "def f():\n    from pycode.kernel import ToolError"),
        ("pycode.tools.services.executor", "from ...models.adapters.factory import ModelFactory"),
        ("pycode.core.state", "from dataclasses import dataclass"),
        ("pycode.agent.adapters.agent", "from pycode.workflows.state import PycodeState"),
        ("pycode.app.composition.runtime", "from evals.coding import runner"),
        ("pycode.__main__", "import archive"),
        ("pycode", "import scripts.check_architecture"),
        ("evals.coding.runner", "import archive.scripts"),
    ],
)
def test_prohibited_dependencies_are_detected(module, source):
    assert dependency_errors(source, module)


@pytest.mark.parametrize(
    ("module", "source"),
    [
        ("pycode.kernel.contracts.messages", "from dataclasses import dataclass"),
        ("pycode.memory.services.memory", "from pycode.context import retained_context_entries"),
        ("pycode.tools.ports.outputs", "from pycode.kernel import ToolResult"),
        ("pycode.tools.adapters.settlement", "from ..domain.execution import batch_execution_mode"),
        ("pycode.agent.adapters.agent", "from langchain.agents import create_agent"),
        (
            "pycode.app.composition.runtime",
            "from pycode.models.adapters.factory import ModelFactory",
        ),
        ("evals.coding.runner", "from pycode.app.composition import build_runtime"),
        ("evals.scripts.compare_eval_runs", "from evals.coding import provenance"),
        ("scripts.verify_release", "from pycode.app.cli import main"),
    ],
)
def test_allowed_dependencies(module, source):
    assert dependency_errors(source, module) == []


def package_file(root, relative, source):
    path = root / "src" / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(source, encoding="utf-8")


def test_public_alias_cannot_reexport_an_adapter(tmp_path):
    package_file(
        tmp_path,
        "pycode/tools/__init__.py",
        "from .adapters.impl import Concrete as Public\n__all__ = ['Public']\n",
    )
    package_file(tmp_path, "pycode/tools/adapters/impl.py", "class Concrete: pass\n")
    package_file(tmp_path, "pycode/session/services/use.py", "from pycode.tools import Public\n")
    errors = check_dependencies(tmp_path)
    assert any("cannot re-export adapter Public" in item for item in errors)
    assert any("cross-feature adapters require composition" in item for item in errors)


def test_unexported_symbol_is_rejected(tmp_path):
    package_file(tmp_path, "pycode/snapshot/__init__.py", "class Hidden: pass\n__all__ = []\n")
    package_file(tmp_path, "pycode/tools/services/use.py", "from pycode.snapshot import Hidden\n")
    assert any("Hidden is not exported" in item for item in check_dependencies(tmp_path))


def test_domain_cannot_hide_io_behind_kernel_api(tmp_path):
    package_file(
        tmp_path, "pycode/kernel/__init__.py", "from .io.files import read\n__all__ = ['read']\n"
    )
    package_file(tmp_path, "pycode/kernel/io/files.py", "def read(): return ''\n")
    package_file(tmp_path, "pycode/memory/domain/value.py", "from pycode.kernel import read\n")
    assert any("domain cannot depend on io" in item for item in check_dependencies(tmp_path))


def test_relative_package_module_alias_checks_internal_layer(tmp_path):
    package_file(tmp_path, "pycode/memory/domain/__init__.py", "from .. import adapters as io\n")
    package_file(tmp_path, "pycode/memory/adapters/__init__.py", "")
    assert any("domain cannot depend on adapters" in item for item in check_dependencies(tmp_path))


def test_type_checking_imports_still_form_module_cycles(tmp_path):
    package_file(
        tmp_path,
        "pycode/memory/services/a.py",
        "from typing import TYPE_CHECKING\nif TYPE_CHECKING:\n"
        "    from .b import B\nclass A: pass\n",
    )
    package_file(tmp_path, "pycode/memory/services/b.py", "from .a import A\nclass B: pass\n")
    assert any(
        "module dependency cycle:" in item and "services.a" in item and "services.b" in item
        for item in check_dependencies(tmp_path)
    )


def test_feature_cycle_is_reported_with_full_path(tmp_path):
    package_file(
        tmp_path,
        "pycode/memory/__init__.py",
        "from .services.use import Memory\n__all__ = ['Memory']\n",
    )
    package_file(
        tmp_path,
        "pycode/context/__init__.py",
        "from .services.use import Context\n__all__ = ['Context']\n",
    )
    package_file(
        tmp_path,
        "pycode/memory/services/use.py",
        "from pycode.context import Context\nclass Memory: pass\n",
    )
    package_file(
        tmp_path,
        "pycode/context/services/use.py",
        "from pycode.memory import Memory\nclass Context: pass\n",
    )
    assert any(
        "feature dependency cycle:" in item and "memory" in item and "context" in item
        for item in check_dependencies(tmp_path)
    )


def test_project_scan_covers_evals_and_skips_generated_data(tmp_path):
    package_file(tmp_path, "pycode/__main__.py", "import evals\n")
    evals = tmp_path / "evals"
    (evals / "coding").mkdir(parents=True)
    (evals / "coding/runner.py").write_text("import archive.scripts\n", encoding="utf-8")
    (evals / "cache").mkdir()
    (evals / "cache/solution.py").write_text("import archive\n", encoding="utf-8")
    assert check_dependencies(tmp_path) == [
        "pycode.__main__:1: pycode cannot import evals",
        "evals.coding.runner:1: evals cannot import archive.scripts",
    ]


@pytest.mark.parametrize(
    "wrapper",
    [
        "Public = Concrete",
        "def Public(): return Concrete()",
        "class Public(Concrete): pass",
    ],
)
def test_public_assignments_factories_and_subclasses_cannot_hide_adapters(tmp_path, wrapper):
    package_file(
        tmp_path,
        "pycode/tools/__init__.py",
        "from .adapters.impl import Concrete\n" + wrapper + "\n__all__ = ['Public']\n",
    )
    package_file(tmp_path, "pycode/tools/adapters/impl.py", "class Concrete: pass\n")
    assert any("cannot re-export adapter Public" in item for item in check_dependencies(tmp_path))


def test_public_module_attribute_alias_cannot_hide_adapter(tmp_path):
    package_file(
        tmp_path,
        "pycode/tools/__init__.py",
        "from .adapters import impl\nPublic = impl.Concrete\n__all__ = ['Public']\n",
    )
    package_file(tmp_path, "pycode/tools/adapters/impl.py", "class Concrete: pass\n")
    assert any("cannot re-export adapter Public" in item for item in check_dependencies(tmp_path))


def test_undefined_public_exports_are_rejected(tmp_path):
    package_file(tmp_path, "pycode/tools/__init__.py", "__all__ = ['Ghost']\n")
    assert any("undefined symbol Ghost" in item for item in check_dependencies(tmp_path))


def test_singular_service_file_obeys_framework_boundary():
    assert dependency_errors("import langchain_core", "pycode.memory.service")


@pytest.mark.parametrize(
    "source",
    [
        "import importlib\nimportlib.import_module('pycode.tools')",
        "from importlib import import_module as load\nload('pycode.tools')",
        "__import__('pycode.tools')",
        "import sys\nsys.modules['pycode.tools'] = object()",
        "def __getattr__(name): return object()",
    ],
)
def test_dynamic_dependency_masking_is_rejected(source):
    assert dependency_errors(source, "pycode.memory.adapters.bad")


def test_verified_user_resource_registration_is_not_a_feature_alias():
    assert (
        dependency_errors(
            "import sys\nsys.modules[module.__name__] = module",
            "pycode.skills.adapters.python_worker",
        )
        == []
    )


@pytest.mark.parametrize("definition", ["", "Ghost = Other\nOther = Ghost\n"])
def test_undefined_or_cyclic_export_import_reports_instead_of_crashing(tmp_path, definition):
    package_file(tmp_path, "pycode/tools/__init__.py", definition + "__all__ = ['Ghost']\n")
    package_file(tmp_path, "pycode/session/services/use.py", "from pycode.tools import Ghost\n")
    errors = check_dependencies(tmp_path)
    assert any("undefined symbol Ghost" in item for item in errors)
    assert any("Ghost has no definition" in item for item in errors)
