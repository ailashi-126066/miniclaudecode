from pathlib import Path
from types import SimpleNamespace

import pytest

from mewcode.commands.handlers.memory import handle_memory
from mewcode.conversation import ConversationManager
from mewcode.memory.auto_memory import MemoryManager
from mewcode.tools.base import StreamEnd, TextDelta


@pytest.fixture
def manager(tmp_path, monkeypatch):
    home = tmp_path / "home"
    home.mkdir()
    monkeypatch.setattr(Path, "home", classmethod(lambda cls: home))
    return MemoryManager(str(tmp_path / "project"))


@pytest.mark.asyncio
async def test_extraction_updates_central_files_and_receives_previous_memories(manager):
    manager._write_memories("### 用户偏好\n- 使用中文回答\n### 项目知识\n- 使用 Python\n")
    conversation = ConversationManager()
    conversation.add_user_message("项目数据库使用 PostgreSQL，代码缩进使用四个空格。")
    prompts = []

    class Client:
        async def stream(self, conversation, system="", tools=None):
            prompts.append(conversation.history[0].content)
            assert tools is None
            yield TextDelta(
                "### 用户偏好\n- 使用中文回答\n- 使用四个空格缩进\n"
                "### 纠正反馈\n- ...\n### 项目知识\n- 使用 Python\n- 使用 PostgreSQL\n"
                "### 参考资料\n- N/A\n"
            )
            yield StreamEnd(stop_reason="end_turn")

    client = Client()
    await manager.extract(client, conversation, "openai")

    assert "使用中文回答" in prompts[0]
    assert "使用 Python" in prompts[0]
    assert manager.user_path.name == manager.project_path.name == "memories.md"
    user_memory = manager.user_path.read_text()
    project_memory = manager.project_path.read_text()
    assert "四个空格" in user_memory and "PostgreSQL" not in user_memory
    assert "PostgreSQL" in project_memory and "使用中文回答" not in project_memory
    assert "..." not in user_memory and "N/A" not in project_memory
    assert not manager.user_mem_dir.exists()
    assert not manager.project_mem_dir.exists()

    await manager.extract(client, conversation, "openai")
    assert len(prompts) == 1


@pytest.mark.asyncio
async def test_failed_extraction_keeps_memories_and_allows_retry(manager):
    manager._write_memories("### 用户偏好\n- 使用中文回答\n")
    before = manager.user_path.read_bytes()
    conversation = ConversationManager()
    conversation.add_user_message("新增偏好")

    class FailingClient:
        async def stream(self, conversation, system="", tools=None):
            yield TextDelta("unfinished output")
            raise RuntimeError("network failure")

    await manager.extract(FailingClient(), conversation, "openai")
    assert manager.user_path.read_bytes() == before
    assert manager._last_extraction_msg_count == 0


def test_clear_only_clears_central_memory_files(manager):
    manager._write_memories("### 用户偏好\n- 使用中文回答\n### 项目知识\n- 使用 Python\n")
    legacy = manager.project_mem_dir / "existing.md"
    legacy.parent.mkdir()
    legacy.write_text("Existing user data")
    config = manager.project_path.parent / "config.yaml"
    config.write_text("providers: []")

    manager.clear()

    assert manager.load() == ""
    assert legacy.read_text() == "Existing user data"
    assert config.read_text() == "providers: []"


@pytest.mark.asyncio
async def test_memory_edit_shows_central_file_paths(manager):
    messages = []
    ctx = SimpleNamespace(args="edit", memory_manager=manager, ui=SimpleNamespace(add_system_message=messages.append))
    await handle_memory(ctx)
    assert str(manager.user_path) in messages[0]
    assert str(manager.project_path) in messages[0]
