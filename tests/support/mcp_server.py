"""Actual official SDK server used by stdio and Streamable HTTP acceptance tests."""

import argparse
import asyncio
import base64
import os
from pathlib import Path

from fastmcp import Context, FastMCP
from fastmcp.utilities.types import Image
from mcp_types import ElicitRequest, ElicitRequestFormParams, InputRequiredResult, ToolAnnotations


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--transport", default="stdio")
    parser.add_argument("--port", type=int, default=8000)
    args = parser.parse_args()
    server = FastMCP("pycode fixture", instructions="Fixture resources stay on demand")
    Path("server.pid").write_text(str(os.getpid()), encoding="utf-8")

    @server.tool(annotations=ToolAnnotations(readOnlyHint=True, destructiveHint=False))
    async def echo(value: str) -> str:
        """Echo supplied text."""
        return "echo:" + value

    @server.tool(annotations=ToolAnnotations(readOnlyHint=True, destructiveHint=False))
    def picture():
        """Return a tiny PNG as real MCP image content."""
        data = base64.b64decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aX1cAAAAASUVORK5CYII="
        )
        return Image(data=data, format="png")

    @server.tool(annotations=ToolAnnotations(readOnlyHint=True, destructiveHint=False))
    def confirm(ctx: Context):
        """Ask for explicit form input using the modern MCP guard pattern."""
        if ctx.input_responses is None:
            return InputRequiredResult(
                input_requests={
                    "answer": ElicitRequest(
                        params=ElicitRequestFormParams(
                            message="Choose a value",
                            requested_schema={
                                "type": "object",
                                "properties": {"value": {"type": "string"}},
                                "required": ["value"],
                            },
                        )
                    )
                },
                request_state="fixture-round",
            )
        response = ctx.input_responses["answer"]
        if response.action == "accept":
            return "accepted:" + response.content["value"]
        return response.action

    def effect(value):
        """Append an effect for recovery counting."""
        path = Path("effects.txt")
        with path.open("a", encoding="utf-8") as stream:
            stream.write(value + "\n")
        return "changed:" + value

    if Path("schema.variant").exists():

        @server.tool(name="mutate")
        def mutate_number(value: int) -> str:
            """Changed schema variant."""
            return effect(str(value))
    else:

        @server.tool(name="mutate")
        def mutate_text(value: str) -> str:
            """Append an effect for recovery counting."""
            return effect(value)

    @server.tool(annotations=ToolAnnotations(readOnlyHint=True, destructiveHint=False))
    async def slow(seconds: float) -> str:
        """Cancellable delay."""
        await asyncio.sleep(seconds)
        return "finished"

    @server.tool(annotations=ToolAnnotations(readOnlyHint=True, destructiveHint=False))
    def big(lines: int) -> str:
        """Return many UTF-8 lines for complete-output testing."""
        return "\n".join("中文行" + str(index) for index in range(lines))

    @server.tool()
    def die() -> str:
        """Deliberate fixture transport death before reply."""
        os._exit(91)

    @server.resource("fixture://note")
    def note() -> str:
        return "Resource content stays on demand"

    @server.prompt()
    def explain(topic: str) -> str:
        return "Explain " + topic

    if args.transport == "stdio":
        server.run(transport="stdio", show_banner=False, log_level="ERROR")
    else:
        server.run(
            transport="http", host="127.0.0.1", port=args.port, show_banner=False, log_level="ERROR"
        )


if __name__ == "__main__":
    main()
