import asyncio
import json

import pytest
from tests.services.test_model_control import store_for
from tests.support.skill_loading import read_skill
from tests.support.skill_resources import VECTORS, write_resource_skill

from pycode.adapters.sandbox.seatbelt import UnavailableSandbox
from pycode.adapters.skills.catalog import FileSkillCatalog
from pycode.adapters.skills.python_tool import PythonSkillTool, PythonSkillToolFactory
from pycode.adapters.tools.output_store import FileToolOutputStore
from pycode.adapters.tools.schema import JsonSchemaValidator
from pycode.core.errors import ToolError
from pycode.core.messages import Message, ToolCall
from pycode.core.sandbox import SandboxSettings
from pycode.core.tools import ToolContext
from pycode.services.events import EventStream
from pycode.services.skill_resources import SkillResources
from pycode.services.skills import SkillService
from pycode.services.tool_executor import ToolExecutor
from pycode.services.tool_registry import ToolRegistry


async def activate(tmp_path, *, code=None, limit_seconds=120):
    root = tmp_path / "skills"
    await write_resource_skill(root, code=code)
    store = store_for(tmp_path)
    await store.append_message(Message("u1", "user", "task"))
    catalog = FileSkillCatalog((("user", root),))
    service = SkillService(store, catalog)
    await service.index()
    loaded = await read_skill(store, catalog, "resource")
    outputs = FileToolOutputStore(tmp_path / "outputs", "s1", store)
    tool = PythonSkillTool(
        loaded["resource_tools"][0],
        loaded,
        catalog,
        outputs,
        UnavailableSandbox(),
        SandboxSettings(),
        timeout=limit_seconds,
    )
    return store, tool, outputs


class Approved:
    async def authorize(self, context, spec, call):
        assert spec.category == "external" and spec.execution_mode == "sequential"


def executor(tool, outputs):
    validator = JsonSchemaValidator()
    registry = ToolRegistry(validator, (tool,))
    return ToolExecutor(registry, validator, Approved(), EventStream("s1", "r1", ()), outputs)


@pytest.mark.parametrize(
    "defect,error",
    [
        ("invalid_json", "invalid_skill_tools"),
        ("missing", "skill_implementation_missing"),
        ("schema", "invalid_tool_schema"),
        ("collision", "tool_name_conflict"),
    ],
)
async def test_bad_declarations_fail_before_activation_or_import(tmp_path, defect, error):
    root = tmp_path / "skills"
    directory = await write_resource_skill(root)
    if defect == "invalid_json":
        await asyncio.to_thread((directory / "tool.json").write_text, "invalid", encoding="utf-8")
    elif defect == "missing":
        await asyncio.to_thread((directory / "references/resource_probe.py").unlink)
    elif defect == "schema":
        declaration = {
            **VECTORS["declaration"],
            "parameters": {"type": "object", "$ref": "https://invalid.example/schema"},
        }
        await asyncio.to_thread(
            (directory / "tool.json").write_text, json.dumps(declaration), encoding="utf-8"
        )
    store = store_for(tmp_path)
    await store.append_message(Message("u1", "user", "task"))
    catalog = FileSkillCatalog((("user", root),))
    service = SkillService(store, catalog)
    try:
        await service.index()
        index = await catalog.scan()
        resources = SkillResources(
            catalog,
            PythonSkillToolFactory(catalog, None, UnavailableSandbox(), SandboxSettings()),
            index.entries,
            reserved_tools=("resource_probe",) if defect == "collision" else (),
        )
        with pytest.raises(ToolError) as caught:
            await resources.resolve_group("pycode_skill_resource")
        assert caught.value.code == error
        assert not any(entry.type == "skill_load" for entry in (await store.snapshot()).entries)
        assert not (tmp_path / "imported.txt").exists()
    finally:
        await store.aclose()


async def test_large_unicode_output_is_complete_and_pageable_after_common_executor(tmp_path):
    code = "def execute(detail):\n    return '\\n'.join('中文行' + str(i) for i in range(2400))\n"
    store, tool, outputs = await activate(tmp_path, code=code)
    context = ToolContext("s1", "r1", "operation", str(tmp_path))
    try:
        result = await executor(tool, outputs).execute(
            context, ToolCall("call", tool.spec.name, VECTORS["argument"]), "tool_calls"
        )
        assert result.output_id and not result.is_error and "中文行2399" not in result.text
        await store.append_message(Message("result", "tool", tool_result=result))
        page = await outputs.read(result.output_id, 2350, 51, 50000)
        assert "中文行2399" in page.text and "中文行2349" in page.text
    finally:
        await store.aclose()


@pytest.mark.parametrize("cancel", [False, True])
async def test_timeout_and_cancel_stop_resource_and_its_owned_child(tmp_path, cancel):
    code = (
        "import subprocess, sys, time\nfrom pathlib import Path\n"
        "def execute(detail):\n"
        "    subprocess.Popen([sys.executable, '-c', "
        '"import time; from pathlib import Path; time.sleep(1.5); '
        "Path('late.txt').write_text('bad')\"])\n"
        "    Path('started.txt').write_text('started')\n    time.sleep(30)\n"
    )
    store, tool, outputs = await activate(tmp_path, code=code, limit_seconds=120 if cancel else 0.4)
    context = ToolContext("s1", "r1", "operation", str(tmp_path))
    task = asyncio.create_task(
        executor(tool, outputs).execute(
            context, ToolCall("call", tool.spec.name, VECTORS["argument"]), "tool_calls"
        )
    )
    try:
        if cancel:
            async with asyncio.timeout(5):
                # The child signals through a real filesystem marker, not an asyncio Event.
                while not await asyncio.to_thread((tmp_path / "started.txt").exists):  # noqa: ASYNC110
                    await asyncio.sleep(0.02)
            task.cancel()
            with pytest.raises(asyncio.CancelledError):
                await task
        else:
            result = await task
            assert result.is_error and result.error_code == "skill_python_timeout"
        await asyncio.sleep(1.6)
        assert not await asyncio.to_thread((tmp_path / "late.txt").exists)
    finally:
        if not task.done():
            task.cancel()
            with pytest.raises(asyncio.CancelledError):
                await task
        await store.aclose()


@pytest.mark.parametrize(
    "code", ["x = 1", "def execute(detail):\n    raise ValueError('defect')\n"]
)
async def test_resource_defect_is_an_error_result_not_success(tmp_path, code):
    store, tool, outputs = await activate(tmp_path, code=code)
    context = ToolContext("s1", "r1", "operation", str(tmp_path))
    try:
        result = await executor(tool, outputs).execute(
            context, ToolCall("call", tool.spec.name, VECTORS["argument"]), "tool_calls"
        )
        assert result.is_error and result.error_code == "skill_python_failed"
        assert "Skill Python execution error" in result.text and result.output_id
    finally:
        await store.aclose()


@pytest.mark.parametrize("file_path,permitted", [("resume.txt", True), ("../outside.txt", False)])
async def test_declared_file_arguments_preserve_workspace_scope(tmp_path, file_path, permitted):
    await asyncio.to_thread(
        (tmp_path / "resume.txt").write_text, "resume content", encoding="utf-8"
    )
    code = (
        "from pathlib import Path\n"
        "def execute(detail, file_path):\n"
        "    return str(Path(file_path).is_absolute()) + ':' + Path(file_path).read_text()\n"
    )
    store, tool, outputs = await activate(tmp_path, code=code)
    context = ToolContext("s1", "r1", "operation", str(tmp_path))
    try:
        result = await executor(tool, outputs).execute(
            context,
            ToolCall("call", tool.spec.name, {"detail": "test", "file_path": file_path}),
            "tool_calls",
        )
        if permitted:
            assert not result.is_error and result.text.strip() == "True:resume content"
        else:
            assert result.is_error and result.error_code == "outside_workspace"
    finally:
        await store.aclose()
