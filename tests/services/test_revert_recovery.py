import pytest
from tests.adapters.snapshot.test_git_tree import git, repository
from tests.support.requests import TestTiming

from pycode.adapters.snapshot.git_tree import GitSnapshotStore
from pycode.adapters.storage.session import JsonlSessionStore
from pycode.core.messages import Message
from pycode.core.revert_state import pending_revert
from pycode.core.session import EntryDraft, SessionHeader
from pycode.services.revert import RevertService


async def test_revert_recovery_finishes_pending_dialogue_step_from_durable_file_record(tmp_path):
    root = repository(tmp_path)
    (root / "file").write_text("old")
    git(root, "add", ".")
    git(root, "commit", "-qm", "initial")
    files = GitSnapshotStore(root, tmp_path / "snapshots")
    before = await files.capture()
    (root / "file").write_text("new")
    after = await files.capture()
    plan = await files.preview({"file": before.tree_id}, expected_tree_id=after.tree_id)
    history = JsonlSessionStore(
        tmp_path / "sessions/s1", SessionHeader("s1", str(root), "t"), TestTiming()
    )
    try:
        leaf = await history.append_message(Message("u1", "user", "before change"))
        await history.append_message(Message("a1", "assistant", "later dialogue"))
        await history.append_entry(
            EntryDraft(
                "custom",
                data={
                    "kind": "revert_started",
                    "restore_id": plan.restore_id,
                    "mode": "both",
                    "target_leaf_id": leaf,
                    "before_tree_id": plan.before_tree_id,
                },
            )
        )
        assert (await files.restore(plan)).status == "completed"
        service = RevertService(history, files)
        assert (await service.recover(plan.restore_id)).status == "completed"
        assert (root / "file").read_text() == "old"
        snapshot = await history.snapshot()
        assert snapshot.entries[-1].data["kind"] == "revert_completed"
        assert snapshot.entries[-2].parent_id == leaf
        assert snapshot.entries[-2].type == "custom_message"
        assert any(entry.message and entry.message.id == "a1" for entry in snapshot.entries)
    finally:
        await history.aclose()


@pytest.mark.parametrize("completed", [False, True])
async def test_legacy_dialogue_journal_does_not_require_a_filesystem_restore(tmp_path, completed):
    root = tmp_path / "plain"
    root.mkdir()
    history = JsonlSessionStore(
        tmp_path / "sessions/s1", SessionHeader("s1", str(root), "t"), TestTiming()
    )
    try:
        target = await history.append_message(Message("u1", "user", "before"))
        await history.append_message(Message("a1", "assistant", "later"))
        started = await history.append_entry(
            EntryDraft(
                "custom",
                data={
                    "kind": "revert_started",
                    "target_leaf_id": target,
                    "mode": "dialogue",
                    "restore_id": None,
                },
            )
        )
        await history.branch(target)
        if completed:
            await history.append_entry(
                EntryDraft(
                    "custom",
                    data={
                        "kind": "revert_completed",
                        "target_leaf_id": target,
                        "mode": "dialogue",
                        "restore_id": None,
                    },
                )
            )
        service = RevertService(history, GitSnapshotStore(root, tmp_path / "snapshots"))
        before = await history.snapshot()
        assert (pending_revert(before) is None) == completed
        assert await service.recover(started.id) is None
        after = await history.snapshot()
        assert pending_revert(after) is None
        if completed:
            assert after == before
        assert not (tmp_path / "snapshots").exists()
    finally:
        await history.aclose()
