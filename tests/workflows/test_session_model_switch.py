from dataclasses import replace

from tests.support.http_provider import chat_completion, protocol_server

from pycode.app.config import AppConfig
from pycode.app.container import (
    build_model_catalog,
    build_model_control,
    build_runtime,
    build_session_repository,
)
from pycode.core.messages import Message
from pycode.core.models import ProviderConfig
from pycode.services.context_projector import ContextProjector


async def test_selected_provider_and_branch_context_drive_real_framework_after_restart(
    tmp_path, monkeypatch
):
    monkeypatch.setenv("PYCODE_SWITCH_API_KEY", "fake-switch-key")
    responses = [chat_completion(deepseek=True), chat_completion(), chat_completion(deepseek=True)]
    with protocol_server(responses) as (url, received):
        base = ProviderConfig(
            "first", "deepseek", "wire-model", url + "/v1", "PYCODE_SWITCH_API_KEY", 32000, 4096
        )
        second = replace(
            base, name="second", implementation="openai", openai_api="chat_completions"
        )
        config = AppConfig(tmp_path, tmp_path / "data", memory_mode="off")
        repository = build_session_repository(config)
        store = await repository.open("s1", create=True)
        catalog = build_model_catalog(config, (base, second))
        control = build_model_control(catalog, store)
        await control.list_models(refresh=False)
        await control.select(base.name, base.model_id)

        async def run(input_id, text):
            provider = await control.restore()
            history = ContextProjector().project(await store.snapshot()).messages
            container = build_runtime(
                config,
                provider,
                session_id="s1",
                run_id=input_id,
                journal=store,
                tools=(),
                consumers=(),
            )
            try:
                return await container.runtime.run(
                    (Message(input_id, "user", text),), container.context, history=history
                )
            finally:
                await container.aclose()

        assert (await run("u1", "first turn")).outcome == "completed"
        first_branch = (await store.snapshot()).leaf_id
        await control.select(second.name, second.model_id)
        assert (await run("u2", "second provider")).outcome == "completed"
        await store.aclose()
        store = await repository.open("s1")
        control = build_model_control(catalog, store)
        assert (await control.restore()).name == "second"
        await store.branch(first_branch)
        assert (await control.restore()).name == "first"
        assert (await run("u3", "new branch")).outcome == "completed"
        final = await store.snapshot()
        assert len([entry for entry in final.entries if entry.type == "message"]) == 6
        contexts = received[-1]["body"]["messages"]
        assert any(message.get("content") == "first turn" for message in contexts)
        assert not any(message.get("content") == "second provider" for message in contexts)
        assert final.entries[-1].message.model_selection.provider == "first"
        await store.aclose()
