"""A reviewed conversation/file restore command owned by session navigation."""

from dataclasses import dataclass
from typing import Literal

from pycode.snapshot import RestorePlan


@dataclass(frozen=True)
class RevertPlan:
    session_id: str
    current_leaf_id: str | None
    target_leaf_id: str | None
    mode: Literal["dialogue", "code", "both"]
    files: RestorePlan | None = None
    transaction_id: str = ""
