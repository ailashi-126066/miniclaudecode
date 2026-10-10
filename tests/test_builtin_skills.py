"""Bundled skill activation, dependencies, command routing, and overrides."""
from pathlib import Path

import pytest
from pydantic import BaseModel

from mewcode.agent import Agent
from mewcode.commands.handlers.review import REVIEW_COMMAND
from mewcode.commands.handlers.skill_register import register_skill_commands
from mewcode.commands.registry import CommandRegistry
from mewcode.skills.directory import register_skill_tools
from mewcode.skills.executor import SkillDependencyError, SkillExecutor, filter_tool_registry
from mewcode.skills.loader import SkillLoader
from mewcode.skills.parser import SkillParseError, parse_skill_file
from mewcode.tools import create_default_registry
from mewcode.tools.load_skill import LoadSkill, LoadSkillParams
from mewcode.tools.base import StreamEnd, TextDelta, ToolCallComplete
from test_toolresult_wiring import CapturingClient


@pytest.fixture
def loader(tmp_path, monkeypatch):
    monkeypatch.setattr('mewcode.skills.loader.USER_SKILLS_DIR', str(tmp_path / 'user-skills'))
    loader = SkillLoader(str(tmp_path))
    loader.load_all()
    return loader


def test_builtin_review_command_uses_skill_and_survives_reload(loader, monkeypatch):
    monkeypatch.setattr('mewcode.commands.handlers.skill_register._REGISTERED_SKILL_NAMES', set())
    registry = CommandRegistry()
    registry.register_sync(REVIEW_COMMAND)
    register_skill_commands(registry, loader)
    for name in ['commit', 'review', 'test', 'backend-interview']:
        command = registry.find(name)
        assert command is not None
        assert '[skill]' in command.description
    assert registry.find('review') is not REVIEW_COMMAND
    register_skill_commands(registry, loader)
    assert '[skill]' in registry.find('review').description


def test_user_and_project_skill_overrides_preserved(loader, tmp_path):
    user = tmp_path / 'user-skills'
    user.mkdir()
    (user / 'test.md').write_text('---\nname: test\ndescription: User tests\n---\nUser prompt')
    loader.reload()
    assert loader.get('test').description == 'User tests'
    assert loader.get_source_label('test') == 'user'
    project = tmp_path / '.mewcode/skills'
    project.mkdir(parents=True)
    (project / 'test.md').write_text('---\nname: test\ndescription: Project tests\n---\nProject prompt')
    loader.reload()
    assert loader.get('test').description == 'Project tests'
    assert loader.get_source_label('test') == 'project'


def test_fork_whitelist_excludes_write_tools_and_disabled_tools(loader):
    registry = create_default_registry()
    review = loader.get('review')
    filtered = filter_tool_registry(registry, review.allowed_tools)
    assert {tool.name for tool in filtered.list_tools()} == {'Bash', 'ReadFile', 'Grep', 'Glob'}
    assert filtered.get('WriteFile') is None
    assert filtered.get('EditFile') is None
    registry.disable('ReadFile')
    with pytest.raises(SkillDependencyError, match='ReadFile'):
        filter_tool_registry(registry, review.allowed_tools)


@pytest.mark.parametrize('bad', ['Bash', '[12]', '[null]'])
def test_invalid_allowed_tools_rejected(tmp_path, bad):
    manifest = tmp_path / 'SKILL.md'
    manifest.write_text(f'---\nname: custom\ndescription: Custom\nallowedTools: {bad}\n---\nbody')
    with pytest.raises(SkillParseError, match='allowedTools'):
        parse_skill_file(manifest)


@pytest.mark.asyncio
async def test_resume_tool_registered_once_and_executes(loader, tmp_path):
    registry = create_default_registry()
    skill = loader.get('backend-interview')
    assert register_skill_tools(skill.source_path.parent, registry) == 1
    assert register_skill_tools(skill.source_path.parent, registry) == 0
    tool = registry.get('parse_resume')
    assert tool.get_schema()['input_schema']['required'] == ['file_path']
    resume = tmp_path / 'resume.md'
    resume.write_text('# Alice\n3 年后端开发\n技术栈：Python、Redis\n## 项目经历\n开发登录接口')
    result = await tool.execute(tool.params_model(file_path=str(resume)))
    assert not result.is_error
    assert '**Name**: Alice' in result.output
    assert '**Experience**: 3 years' in result.output
    assert 'Python' in result.output and 'Redis' in result.output


@pytest.mark.asyncio
@pytest.mark.parametrize('through_load_skill', [False, True])
async def test_backend_interview_activation_runs_resume_tool(loader, tmp_path, monkeypatch, through_load_skill):
    monkeypatch.chdir(tmp_path)
    resume = tmp_path / 'resume.txt'
    resume.write_text('# Alice\n3 年经验\n技术栈：Python、Redis')
    client = CapturingClient([
        [ToolCallComplete('resume_1', 'parse_resume', {'file_path': str(resume)}), StreamEnd('tool_use', 1, 1)],
        [TextDelta('Interview questions for Alice'), StreamEnd('end_turn', 1, 1)],
    ])
    registry = create_default_registry()
    agent = Agent(client, registry, 'anthropic', work_dir=str(tmp_path))
    executor = SkillExecutor(agent, client, 'anthropic')
    if through_load_skill:
        tool = LoadSkill()
        tool.set_loader(loader)
        tool.set_agent(agent)
        tool.set_executor(executor)
        result = await tool.execute(LoadSkillParams(name='backend-interview'))
        assert not result.is_error
        assert result.output == 'Interview questions for Alice'
    else:
        result = await executor.execute_fork(loader.get('backend-interview'), str(resume))
        assert result == 'Interview questions for Alice'
    assert {s['name'] for s in registry.get_all_schemas()} >= {'parse_resume'}
    tool_results = [tr for m in client.requests[1].history for tr in m.tool_results]
    assert '**Name**: Alice' in tool_results[0].content
    assert '**Experience**: 3 years' in tool_results[0].content
