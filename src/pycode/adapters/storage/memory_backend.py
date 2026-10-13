"""Native file tools can inspect memory Markdown; service commands own all writes."""

import re

from pycode.adapters.tools.backend import PycodeBackend
from pycode.core.errors import ToolError


class MemoryBackend(PycodeBackend):
    def __init__(self, root):
        super().__init__(root, read_only=True)

    def resolve(self, value):
        if value != "/" and not re.fullmatch(r"/(?:MEMORY|[a-f0-9]{32})\.md", value):
            raise ToolError("memory_not_accessible", "Only memory Markdown is exposed")
        return super().resolve(value)
