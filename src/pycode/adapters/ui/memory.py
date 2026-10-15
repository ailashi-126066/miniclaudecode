"""Memory inspection reads the same JSON records as recall and management tools."""

from textual.app import ComposeResult
from textual.containers import VerticalScroll
from textual.widgets import Static


class MemoryPanel(VerticalScroll):
    DEFAULT_CSS = """
    MemoryPanel {height: 20; border: solid $primary; padding: 1; overflow-y: auto;}
    """

    def __init__(self, report):
        super().__init__(id="memory-panel")
        self.report = report

    def compose(self) -> ComposeResult:
        yield Static("Memory mode: " + self.report["mode"], markup=False)
        if not self.report["records"]:
            yield Static("No saved memories.", markup=False)
        for record in self.report["records"]:
            kind = record.get("kind", "SemanticMemory")
            yield Static(
                f"[{record['scope']} · {kind}] {record['key']} · {record['memory_id']}\n"
                + record["content"],
                markup=False,
            )
