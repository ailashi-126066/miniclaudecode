"""Completed worktree tasks return results without committing or merging changes."""
import subprocess

import pytest

from mewcode.agents.task_manager import TaskManager
from mewcode.agents.notification import format_task_notification


def git(repo, *args):
    return subprocess.run(['git', *args], cwd=repo, check=True, capture_output=True, text=True).stdout.strip()


@pytest.mark.asyncio
async def test_worktree_task_preserves_uncommitted_changes_without_integration(tmp_path):
    repo = tmp_path / 'repo'
    repo.mkdir()
    git(repo, 'init')
    git(repo, 'config', 'user.email', 'test@example.invalid')
    git(repo, 'config', 'user.name', 'Test User')
    (repo / 'file.txt').write_text('base\n')
    git(repo, 'add', 'file.txt')
    git(repo, 'commit', '-m', 'base')
    base = git(repo, 'rev-parse', 'HEAD')
    worker = tmp_path / 'worker'
    git(repo, 'worktree', 'add', '-b', 'worker-a', str(worker))

    class FakeAgent:
        team_name = ''
        _team_manager = None
        total_input_tokens = 0
        total_output_tokens = 0

        async def run_to_completion(self, task):
            (worker / 'file.txt').write_text('worker change\n')
            return 'Changed file.txt in worker-a; ready for review.'

    manager = TaskManager()
    task_id = manager.launch(FakeAgent(), 'change file', 'worker-a')
    await manager._async_tasks[task_id]
    completed = manager.poll_completed()
    assert len(completed) == 1 and completed[0].status == 'completed'
    assert 'Changed file.txt' in completed[0].result
    assert (repo / 'file.txt').read_text() == 'base\n'
    assert (worker / 'file.txt').read_text() == 'worker change\n'
    assert git(repo, 'rev-parse', 'HEAD') == base
    assert git(worker, 'rev-parse', 'HEAD') == base
    assert git(worker, 'status', '--porcelain')
    assert not any(branch.startswith('integration-') for branch in git(repo, 'branch', '--format=%(refname:short)').splitlines())
    assert 'Integration report' not in format_task_notification(completed[0])
