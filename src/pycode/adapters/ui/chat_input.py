# Source: MewCode local app.py; SHA-256:
# 2680a87b05640a668323ae5712bed84eec2bc424cb47ec70e3d876450ec81696
# Adaptation: submit/newline only; commands go through the injected application.
# 来源：公众号@小林coding
# 后端八股网站：xiaolincoding.com
# Agent网站：xiaolinnote.com
# 简历模版：jianli.xiaolinnote.com
"""Multiline prompt entry emits explicit submissions without touching history."""

from textual.binding import Binding
from textual.message import Message
from textual.widgets import TextArea


class ChatInput(TextArea):
    BINDINGS = [
        Binding("enter", "submit", "Send", priority=True),
        Binding("shift+enter", "newline", "Newline", priority=True),
        Binding("ctrl+j", "newline", "Newline", priority=True),
    ]

    class Submitted(Message):
        def __init__(self, text):
            super().__init__()
            self.text = text

    def action_submit(self):
        if self.text.strip():
            text = self.text
            self.clear()
            self.post_message(self.Submitted(text))

    def action_newline(self):
        self.insert("\n")
