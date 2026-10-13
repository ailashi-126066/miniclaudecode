import json

from tests.adapters.snapshot.test_git_tree import git, repository
from tests.support.execution import provider
from tests.support.http_provider import chat_completion, protocol_server

from pycode.app.config import AppConfig
from pycode.app.container import build_session_repository, open_agent_session
from pycode.core.messages import Message


def reply(name, arguments):
    response = chat_completion(tool=True)
    response.body = (
        response.body.decode()
        .replace('"name": "echo"', json.dumps("name") + ": " + json.dumps(name))
        .replace(
            json.dumps('{"value":"wire"}'),
            json.dumps(json.dumps(arguments)),
        )
        .encode()
    )
    return response


async def test_read_receipt_survives_approval_restart_and_exact_edit_gets_snapshot_boundary(
    tmp_path, monkeypatch
):
    monkeypatch.setenv("PYCODE_RECOVERY_TEST_KEY", "test-key")
    root = repository(tmp_path)
    (root / "file.txt").write_text("before target after", encoding="utf-8")
    git(root, "add", ".")
    git(root, "commit", "-qm", "initial")
    config = AppConfig(root, tmp_path / "data", memory_mode="off")
    responses = [
        reply("read_file", {"file_path": "/workspace/file.txt"}),
        reply(
            "edit_file",
            {
                "file_path": "/workspace/file.txt",
                "old_string": "target",
                "new_string": "changed",
            },
        ),
        chat_completion(),
    ]
    with protocol_server(responses) as (url, requests):
        selected = provider(url)
        session = await open_agent_session(
            config, (selected,), "s1", create=True, refresh_models=False
        )
        pending = await session.run(Message("u1", "user", "edit the file"))
        assert (
            pending.outcome == "needs_input" and pending.pending[0]["call"]["name"] == "edit_file"
        )
        restored = session
        assert (
            await restored.resume(
                {"operation_id": pending.pending[0]["operation_id"], "decision": "approve"}
            )
        ).outcome == "completed"
        assert len(requests) == 3 and (root / "file.txt").read_text() == "before changed after"
        await restored.aclose()
        store = await build_session_repository(config).open("s1")
        snapshot = await store.snapshot()
        boundaries = [
            entry for entry in snapshot.entries if entry.data.get("kind") == "file_snapshot"
        ]
        assert len(boundaries) == 1 and boundaries[0].data["paths"] == ("file.txt",)
        assert boundaries[0].data["before"] != boundaries[0].data["after"]
        await store.aclose()
