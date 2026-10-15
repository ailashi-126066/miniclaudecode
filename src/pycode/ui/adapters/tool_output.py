"""One tool card; expanded output is read from history, never executed again."""

from textual.app import ComposeResult
from textual.containers import Vertical
from textual.widgets import Button, Static

from pycode.kernel import DomainError


class ToolOutputCard(Vertical):
    DEFAULT_CSS = "ToolOutputCard { height: auto; } ToolOutputCard Button { height: 3; }"

    def __init__(self, entry_id, result, reader):
        super().__init__()
        self.entry_id = entry_id
        self.result = result
        self.reader = reader
        self.expanded = False
        self.loaded = ""
        self.next_offset = 1
        self.next_byte_offset = 0
        self.has_more = False

    def preview(self):
        lines = self.result["text"].splitlines()
        text = "\n".join(lines[:20])
        if len(lines) > 20:
            text += "\n[输出已折叠]"
        if self.result.get("output_id"):
            text += "\nFull output: " + self.result["output_id"]
        return text

    def is_long(self):
        return len(self.result["text"].splitlines()) > 20 or len(self.result["text"]) > 4000

    def compose(self) -> ComposeResult:
        yield Static(self.preview(), markup=False, id="body")
        yield Button("展开输出", id="toggle-output")
        yield Button("加载后续内容", id="more-output")

    def on_mount(self):
        self.refresh_buttons()

    def refresh_buttons(self):
        self.query_one("#toggle-output", Button).display = self.is_long()
        self.query_one("#toggle-output", Button).label = "收起输出" if self.expanded else "展开输出"
        self.query_one("#more-output", Button).display = self.expanded and self.has_more

    async def read_next(self):
        output_id = self.result.get("output_id")
        if output_id is None:
            self.loaded = self.result["text"]
            self.has_more = False
        else:
            result = await self.reader(
                output_id, offset=self.next_offset, byte_offset=self.next_byte_offset
            )
            self.loaded += result["text"]
            self.next_offset = result["next_offset"]
            self.next_byte_offset = result["next_byte_offset"]
            self.has_more = result["has_more"]
        text = self.loaded
        if self.has_more:
            text += "\n[还有后续内容，请继续加载]"
        self.query_one("#body", Static).update(text)
        self.refresh_buttons()

    async def on_button_pressed(self, event):
        if event.button.id not in {"toggle-output", "more-output"}:
            return
        event.stop()
        try:
            if event.button.id == "more-output":
                await self.read_next()
                return
            self.expanded = not self.expanded
            if self.expanded:
                if not self.loaded:
                    await self.read_next()
                else:
                    self.query_one("#body", Static).update(self.loaded)
            else:
                self.query_one("#body", Static).update(self.preview())
            self.refresh_buttons()
        except DomainError as error:
            self.query_one("#body", Static).update(f"输出读取失败：{error.code}: {error}")
