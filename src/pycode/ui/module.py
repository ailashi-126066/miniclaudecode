"""Presentation assembly accepts application ports and owns no execution services."""

from typing import TextIO

from pycode.ui.adapters.console import ConsoleOutput
from pycode.ui.adapters.tui import PyApp
from pycode.ui.ports.ui import InteractiveSessions


class UIModule:
    def __init__(self, output: TextIO):
        self.output = output

    def console(self, output_format):
        return ConsoleOutput(output_format, self.output)

    def tui(self, sessions: InteractiveSessions, *, session_id=None):
        return PyApp(sessions, session_id=session_id)
