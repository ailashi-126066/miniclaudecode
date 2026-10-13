"""Run physical macOS write/network checks; other platforms remain unverified."""

import argparse
import asyncio
import json
import platform
import shlex
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from uuid import uuid4

from pycode.adapters.sandbox.seatbelt import SeatbeltSandbox
from pycode.core.sandbox import SandboxSettings


async def execute(sandbox, workspace, command, *, network=False):
    argv = await sandbox.launch(
        ("/bin/bash", "-c", command), str(workspace), SandboxSettings("on", network)
    )
    process = await asyncio.create_subprocess_exec(
        *argv, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE
    )
    try:
        async with asyncio.timeout(10):
            stdout, stderr = await process.communicate()
    except TimeoutError:
        process.kill()
        await process.wait()
        raise
    return {
        "exit_code": process.returncode,
        "stdout": stdout.decode(errors="replace"),
        "stderr": stderr.decode(errors="replace"),
    }


async def verify(root):
    workspace = root / "tests/.runtime-tests" / ("seatbelt-" + uuid4().hex)
    workspace.mkdir(parents=True)
    outside = workspace.parent / (workspace.name + "-outside")
    sandbox = SeatbeltSandbox()
    allowed = await execute(
        sandbox, workspace, "printf allowed > " + shlex.quote(str(workspace / "inside.txt"))
    )
    denied = await execute(sandbox, workspace, "printf denied > " + shlex.quote(str(outside)))
    assert allowed["exit_code"] == 0 and (workspace / "inside.txt").read_text() == "allowed"
    assert denied["exit_code"] != 0 and not outside.exists()

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, format, *args):
            return

        def do_GET(self):
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b"local-probe")

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    worker = threading.Thread(target=server.serve_forever, daemon=True)
    worker.start()
    try:
        command = "/usr/bin/curl --silent --show-error --max-time 3 http://127.0.0.1:" + str(
            server.server_port
        )
        no_network = await execute(sandbox, workspace, command)
        with_network = await execute(sandbox, workspace, command, network=True)
        assert no_network["exit_code"] != 0
        assert with_network["exit_code"] == 0 and with_network["stdout"] == "local-probe"
        return {
            "status": "passed",
            "write_inside": allowed,
            "write_outside": denied,
            "network_denied": no_network,
            "network_allowed": with_network,
        }
    finally:
        server.shutdown()
        server.server_close()
        worker.join()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if sys.platform != "darwin":
        report = {"status": "unverified", "reason": "Physical Seatbelt enforcement requires macOS"}
    else:
        report = asyncio.run(verify(Path(__file__).resolve().parents[1]))
    report["platform"] = platform.platform()
    args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report))
    return 0 if report["status"] == "passed" else 2


if __name__ == "__main__":
    raise SystemExit(main())
