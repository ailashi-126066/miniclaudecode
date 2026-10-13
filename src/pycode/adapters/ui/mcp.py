"""Render native MCP questions and return explicit accept/decline/cancel answers."""

import json

from textual.containers import Vertical
from textual.message import Message
from textual.widgets import Button, Static, TextArea

from pycode.core.approvals import validate_operation_answer
from pycode.core.errors import StorageError
from pycode.core.values import json_dumps


class MCPElicitationPanel(Vertical):
    DEFAULT_CSS = """
    MCPElicitationPanel {height: auto;}
    MCPElicitationPanel Static {height: auto;}
    MCPElicitationPanel TextArea {height: 6;}
    """

    class Responded(Message):
        def __init__(self, answer):
            super().__init__()
            self.answer = answer

    def __init__(self, payload):
        super().__init__(id="mcp-elicitation-inline")
        self.payload = payload

    def compose(self):
        initial = {}
        for request in self.payload["requests"]:
            yield Static(request["message"], markup=False)
            if request["mode"] == "url":
                yield Static(request["url"], markup=False)
            else:
                yield Static(
                    json_dumps(request["requested_schema"], ensure_ascii=False), markup=False
                )
                initial[request["key"]] = {}
        yield TextArea(json_dumps(initial, ensure_ascii=False, indent=2), id="mcp-form-content")
        yield Button("接受并回答", id="mcp-accept", variant="primary")
        yield Button("拒绝回答", id="mcp-decline")
        yield Button("取消请求", id="mcp-cancel")
        yield Static("", id="mcp-form-error", markup=False)

    def on_button_pressed(self, event):
        actions = {"mcp-accept": "accept", "mcp-decline": "decline", "mcp-cancel": "cancel"}
        action = actions.get(event.button.id)
        if action is None:
            return
        event.stop()
        try:
            content = {}
            if action == "accept":
                content = json.loads(self.query_one("#mcp-form-content", TextArea).text)
                if not isinstance(content, dict):
                    raise ValueError("Answers must be keyed JSON objects")
            responses = {}
            for request in self.payload["requests"]:
                response = {"action": action}
                if action == "accept" and request["mode"] == "form":
                    response["content"] = content.get(request["key"])
                responses[request["key"]] = response
            answer = {"responses": responses}
            validate_operation_answer(self.payload, answer)
        except (ValueError, StorageError) as error:
            self.query_one("#mcp-form-error", Static).update(str(error))
            return
        self.post_message(self.Responded(answer))
