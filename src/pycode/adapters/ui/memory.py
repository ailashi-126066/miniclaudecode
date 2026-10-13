"""Inline memory review keeps approval, edits and scope choice explicit."""

from textual.app import ComposeResult
from textual.containers import Horizontal, VerticalScroll
from textual.message import Message
from textual.widgets import Button, Select, Static, TextArea


class MemoryPanel(VerticalScroll):
    DEFAULT_CSS = """
    MemoryPanel {height: 20; border: solid $primary; padding: 1; overflow-y: auto;}
    MemoryPanel VerticalScroll {height: 6;}
    MemoryPanel TextArea {height: 5;}
    MemoryPanel Horizontal {height: auto;}
    """

    class Reviewed(Message):
        def __init__(self, candidate_id, decision, content, scope):
            super().__init__()
            self.candidate_id = candidate_id
            self.decision = decision
            self.content = content
            self.scope = scope

    def __init__(self, report):
        super().__init__(id="memory-panel")
        self.report = report
        self.candidates = {}
        for candidate in report["candidates"]:
            if candidate["status"] == "pending":
                self.candidates[candidate["candidate_id"]] = candidate

    def compose(self) -> ComposeResult:
        yield Static("Memory mode: " + self.report["mode"], markup=False)
        with VerticalScroll():
            for record in self.report["records"]:
                yield Static(
                    f"[{record['scope']}] {record['key']} · {record['memory_id']}\n"
                    + record["content"],
                    markup=False,
                )
        if self.candidates:
            choices = []
            for identifier, candidate in self.candidates.items():
                choices.append((candidate["key"] + " · " + candidate["action"], identifier))
            yield Select(choices, prompt="Review a memory candidate", id="memory-candidate")
            yield TextArea(id="memory-content")
            yield Select(
                [("User", "user"), ("Project", "project")], value="user", id="memory-scope"
            )
            yield Static("", id="memory-evidence", markup=False)
            with Horizontal():
                yield Button("Approve edited memory", id="memory-approve", variant="primary")
                yield Button("Reject", id="memory-reject", variant="error")

    def on_select_changed(self, event):
        if event.select.id != "memory-candidate" or event.value not in self.candidates:
            return
        candidate = self.candidates[event.value]
        self.query_one("#memory-content", TextArea).load_text(candidate["content"])
        self.query_one("#memory-scope", Select).value = candidate["scope"]
        lines = [candidate["reason"] or "Review proposed durable knowledge"]
        for evidence in candidate["evidence"]:
            lines.append(evidence["entry_id"] + ": " + evidence["quote"])
        self.query_one("#memory-evidence", Static).update("\n".join(lines))

    def on_button_pressed(self, event):
        if event.button.id not in {"memory-approve", "memory-reject"}:
            return
        event.stop()
        identifier = self.query_one("#memory-candidate", Select).value
        if identifier not in self.candidates:
            return
        decision = "approve" if event.button.id == "memory-approve" else "reject"
        self.post_message(
            self.Reviewed(
                identifier,
                decision,
                self.query_one("#memory-content", TextArea).text,
                self.query_one("#memory-scope", Select).value,
            )
        )
