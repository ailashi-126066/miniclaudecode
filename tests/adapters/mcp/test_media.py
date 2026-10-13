"""MCPAdapter media survives settlement and model conversion without a second copy."""

import os
from dataclasses import replace

from tests.adapters.mcp.test_connection import configured

from pycode.adapters.mcp.manager import MCPAdapterConnections
from pycode.core.messages import Message
from pycode.core.models import ModelCapabilities, ModelSelection
from pycode.workflows.messages import to_framework


async def test_real_stdio_image_is_preserved_and_forwarded_only_to_capable_model(tmp_path):
    connections = MCPAdapterConnections((configured(tmp_path),), dict(os.environ))
    try:
        catalog = await connections.catalog()
        declaration = next(item for item in catalog["tools"] if item["original_name"] == "picture")
        result = await connections.call(declaration, {})
        media = result.details["mcp_content_blocks"]
        assert len(media) == 1 and media[0]["type"] == "image"
        assert media[0]["mime_type"] == "image/png" and media[0]["base64"].startswith("iVBOR")
        result = replace(result, call_id="c1")
        message = Message("t1", "tool", tool_result=result)
        selection = ModelSelection(
            "test", "fixed", "openai", capabilities=ModelCapabilities(supports_images=True)
        )
        converted = to_framework(message, selection)
        assert converted.content[1]["base64"] == media[0]["base64"]
        text_only = to_framework(message, replace(selection, capabilities=ModelCapabilities()))
        assert isinstance(text_only.content, str) and "image/png" in text_only.content
        assert result.details["mcp_content_blocks"] == media
    finally:
        await connections.aclose()
