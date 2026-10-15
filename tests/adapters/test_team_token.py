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


def test_existing_token_directory_is_tightened_to_owner_only(tmp_path):
    directory = token_path(tmp_path, 2024).parent
    directory.mkdir(mode=0o755)
    directory.chmod(0o755)
    issue_token(tmp_path, 2024)
    assert stat.S_IMODE(directory.stat().st_mode) == 0o700


def test_token_is_not_sent_to_loopback_names_the_host_does_not_listen_on(tmp_path):
    issue_token(tmp_path, 2024)
    specs = async_agent_specs(
        [
            TeamAgent("worker", "Worker", "worker", url)
            for url in ("http://[::1]:2024", "http://127.0.0.2:2024", "http://localhost.:2024")
        ],
        tmp_path,
    )
    assert [spec["headers"] for spec in specs] == [{}, {}, {}]
