"""Explicit review of uncertain effects and file revert plans."""

from textual.app import ComposeResult
from textual.containers import Horizontal, Vertical
from textual.message import Message
from textual.widgets import Button, Static


class RevertPanel(Vertical):
    DEFAULT_CSS = """
    RevertPanel {height: auto; padding: 1; border: solid $primary;}
    RevertPanel Static {height: auto;}
    RevertPanel Horizontal {height: auto;}
    """

    class Responded(Message):
        def __init__(self, action):
            super().__init__()
            self.action = action

    def __init__(self, plan=None, *, transaction_id=None):
        super().__init__(id="revert-inline")
        self._plan = plan
        self._transaction = transaction_id

    def compose(self) -> ComposeResult:
        if self._transaction is not None:
            yield Static("Interrupted revert: review current files before recovery.", markup=False)
            with Horizontal():
                yield Button("Continue restore", id="restore-continue", variant="primary")
                yield Button("Compensate", id="restore-compensate", variant="warning")
            return
        plan = self._plan
        if plan is None:
            raise ValueError("Revert preview requires a plan or recovery transaction")
        yield Static(
            f"Revert mode: {plan.mode} · target {plan.target_leaf_id or 'root'}", markup=False
        )
        conflicts = plan.files.conflicts if plan.files is not None else ()
        if plan.files is not None:
            for item in plan.files.files:
                yield Static(
                    item.path + (" · binary" if item.binary else "") + "\n" + item.patch,
                    markup=False,
                )
        if conflicts:
            yield Static("Conflicts: " + ", ".join(conflicts), markup=False)
        with Horizontal():
            yield Button(
                "Apply reviewed revert",
                id="revert-apply",
                disabled=bool(conflicts),
                variant="primary",
            )
            yield Button("Discard preview", id="revert-discard")

    def on_button_pressed(self, event):
        event.stop()
        self.post_message(self.Responded(event.button.id))
