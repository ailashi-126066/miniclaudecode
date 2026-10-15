"""Session-owned execution policies exposed through consumer capabilities."""

from pycode.kernel import SessionEntry, SessionHeader, SessionSnapshot
from pycode.session.domain.execution_recovery import pending_run_entry, run_belongs_to_session
from pycode.session.domain.revert_state import pending_revert


class SessionRunOwnership:
    @staticmethod
    def owns(header: SessionHeader, entry: SessionEntry) -> bool:
        return run_belongs_to_session(header, entry)


class SessionCatalogWriteGuard:
    @staticmethod
    def can_initialize(snapshot: SessionSnapshot) -> bool:
        return pending_run_entry(snapshot) is None and pending_revert(snapshot) is None
