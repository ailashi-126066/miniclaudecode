"""Validate this feature's configuration at a persisted execution boundary."""

from pycode.kernel import SessionEntry, StorageError


def validate_mcp_recovery(pending: SessionEntry | None, current_hash: str) -> None:
    if pending is not None and pending.data.get("mcp_config_hash", "") != current_hash:
        raise StorageError(
            "execution_configuration_changed", "MCP configuration changed during execution"
        )
