"""The local Worker host token is private, atomic, per port and picked up by clients."""

import stat

from pycode.adapters.team.token import issue_token, read_token, revoke_token, token_path
from pycode.app.container import async_agent_specs
from pycode.core.team import TeamAgent


def test_issued_token_is_owner_only_and_replaced_on_restart(tmp_path):
    first = issue_token(tmp_path, 2024)
    path = token_path(tmp_path, 2024)
    assert stat.S_IMODE(path.stat().st_mode) == 0o600
    assert stat.S_IMODE(path.parent.stat().st_mode) == 0o700
    assert read_token(tmp_path, 2024) == first and len(first) >= 32
    second = issue_token(tmp_path, 2024)
    assert second != first and read_token(tmp_path, 2024) == second
    assert [item.name for item in path.parent.iterdir()] == ["2024.token"]
    revoke_token(tmp_path, 2024, first)
    assert read_token(tmp_path, 2024) == second
    revoke_token(tmp_path, 2024, second)
    assert read_token(tmp_path, 2024) is None


def test_local_agent_specs_carry_the_published_token_only_for_loopback(tmp_path):
    token = issue_token(tmp_path, 2024)

    def agent(url, headers=None):
        return TeamAgent("worker", "Worker", "worker", url, headers=headers or {})

    local, named, remote, other_port, explicit = async_agent_specs(
        [
            agent("http://127.0.0.1:2024"),
            agent("http://localhost:2024/"),
            agent("https://team.example:2024"),
            agent("http://127.0.0.1:2025"),
            agent("http://127.0.0.1:2024", {"authorization": "Bearer configured"}),
        ],
        tmp_path,
    )
    assert local["headers"] == named["headers"] == {"Authorization": "Bearer " + token}
    assert remote["headers"] == other_port["headers"] == {}
    assert explicit["headers"] == {"authorization": "Bearer configured"}
