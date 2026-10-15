"""Resume only the failed probe's persisted memory jobs and capture safe traceback locations."""

import asyncio
import json
import traceback
from datetime import datetime, timezone
from pathlib import Path

from pycode.app.config import AppConfig, load_providers
from pycode.app.container import open_agent_session

ROOT = Path(__file__).resolve().parents[2]
DIRECTORY = ROOT / "tests/.runtime-tests/live/20261014T035034484130-live-application"
SESSION_ID = DIRECTORY.name + "-next"


async def main():
    config = AppConfig(
        DIRECTORY / "workspace",
        DIRECTORY / "data",
        credential_workspace=ROOT,
        allow_delegation=False,
    )
    provider = load_providers(ROOT / "evals/data/providers-final.json")[0]
    session = None
    report = {"acceptance": "resume_only_memory_jobs", "session_id": SESSION_ID}
    try:
        session = await open_agent_session(
            config,
            (provider,),
            SESSION_ID,
            refresh_models=False,
            startup_hooks=False,
            connect_mcp=False,
        )
        await session.collect_memory()
        report["status"] = "passed"
    except Exception as error:
        report.update(
            status="failed",
            error_type=type(error).__name__,
            error_code=getattr(error, "code", None),
        )
        if isinstance(error, AttributeError):
            report["attribute_name"] = getattr(error, "name", None)
        report["frames"] = [
            {"file": frame.filename, "line": frame.lineno, "function": frame.name}
            for frame in traceback.extract_tb(error.__traceback__)[-8:]
        ]
    finally:
        if session is not None:
            await session.aclose()
    path = DIRECTORY / (datetime.now(timezone.utc).strftime("recovery-%Y%m%dT%H%M%S%f") + ".json")
    path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({**report, "report": str(path)}, ensure_ascii=False))


if __name__ == "__main__":
    asyncio.run(main())
