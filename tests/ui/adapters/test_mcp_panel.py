"""MCP form decisions are explicit and retain the framework's request keys."""

import pytest
from textual.app import App
from textual.widgets import TextArea

from pycode.ui.adapters.mcp import MCPElicitationPanel


class FormApp(App):
    def __init__(self):
        super().__init__()
        self.answer = None

    def compose(self):
        yield MCPElicitationPanel(
            {
                "type": "mcp_elicitation",
                "kind": "mcp_elicitation",
                "requests": [
                    {
                        "key": "one",
                        "message": "Choose a value",
                        "mode": "form",
                        "requested_schema": {
                            "type": "object",
                            "properties": {"value": {"type": "string"}},
                        },
                    }
                ],
            }
        )

    def on_mcpelicitation_panel_responded(self, event):
        self.answer = event.answer


@pytest.mark.parametrize("action", ["accept", "decline", "cancel"])
async def test_native_mcp_panel_returns_one_explicit_answer(action):
    app = FormApp()
    async with app.run_test(size=(100, 35)) as pilot:
        app.query_one(TextArea).load_text('{"one": {"value": "human"}}')
        await pilot.click("#mcp-" + action)
        await pilot.pause()
        expected = {"action": action}
        if action == "accept":
            expected["content"] = {"value": "human"}
        assert app.answer == {"responses": {"one": expected}}
