# Source: MewCode local app.py and tests/test_tui_framework.py; hashes in docs/source-map.md.
# Adaptation: only public application commands and native projections; owned UI tasks and Events.
# 来源：公众号@小林coding
# 后端八股网站：xiaolincoding.com
# Agent网站：xiaolinnote.com
# 简历模版：jianli.xiaolinnote.com
"""Interactive terminal presentation shares AgentSession with non-interactive CLI."""

import asyncio
import logging
import shlex
from uuid import uuid4

from textual.app import App, ComposeResult
from textual.binding import Binding
from textual.containers import Horizontal, Vertical, VerticalScroll
from textual.message import Message as UIMessage
from textual.widgets import Button, Footer, Input, Markdown, Select, Static, Tree

from pycode.adapters.ui.approval import ApprovalPanel
from pycode.adapters.ui.chat_input import ChatInput
from pycode.adapters.ui.mcp import MCPElicitationPanel
from pycode.adapters.ui.memory import MemoryPanel
from pycode.adapters.ui.plans import PlanPanel
from pycode.adapters.ui.questions import QuestionPanel
from pycode.adapters.ui.recovery import RevertPanel
from pycode.adapters.ui.theme import themes
from pycode.adapters.ui.tool_output import ToolOutputCard
from pycode.adapters.ui.usage import UsageScreen
from pycode.core.errors import DomainError, StorageError
from pycode.core.messages import Message
from pycode.core.runtime import RunResult
from pycode.core.values import json_dumps
from pycode.ports.ui import InteractiveSession, InteractiveSessions


def token_text(usage):
    parts = []
    for label, key in (
        ("input", "input_tokens"),
        ("output", "output_tokens"),
        ("cache hit", "cache_read_tokens"),
        ("cache miss", "cache_miss_tokens"),
        ("cache write", "cache_write_tokens"),
        ("reasoning", "reasoning_tokens"),
        ("total", "total_tokens"),
    ):
        value = usage.get(key)
        parts.append(f"{label} {value if value is not None else 'unavailable'}")
    return " · ".join(parts)


def context_text(report):
    if report["tokens"] is None or report["window"] is None:
        return "ctx unavailable"
    return (
        f"ctx {report['tokens']}/{report['window']} ({report['fraction'] * 100:.1f}%) "
        f"· {report['source']}"
    )


class PyApp(App):
    TITLE = "pycode"
    CSS = """
    Screen {background: $background; color: $foreground;}
    #title {height: 1; color: $primary; text-style: bold;}
    #main {height: 1fr;}
    #sidebar {width: 28; background: $surface; padding: 0 1;}
    #sidebar Button, #sidebar Select, #sidebar Input {width: 100%; margin-bottom: 1;}
    #session-tree {height: 16; min-height: 8;}
    #conversation {height: 1fr; padding: 0 2;}
    #back-to-bottom {display: none; height: 3;}
    #conversation Static {height: auto;}
    #conversation Markdown {margin-bottom: 1;}
    #live-thinking {height: auto; color: $text-muted;}
    #live-text {height: auto;}
    #pending {height: auto; max-height: 22; overflow-y: auto;}
    #entry {height: 7; border-top: solid $primary-muted;}
    #chat-input {height: 1fr; border: solid $primary-muted;}
    #chat-input:focus {border: solid $primary;}
    #actions {height: 3;}
    #actions Button {width: auto;}
    #queue-mode {width: 20;}
    #status {height: auto; max-height: 4; color: $text-muted;}
    #notice {height: auto; max-height: 6;}
    #notice.error {color: $error;}
    #notice.success {color: $success;}
    #notice.warning {color: $warning;}
    .message-heading {color: $primary; margin-top: 1;}
    .message-usage {color: $text-muted; margin-bottom: 1;}
    .tool-failed {color: $error;}
    .tool-ok {color: $success;}
    """
    BINDINGS = [
        Binding("ctrl+c", "stop", "Stop", priority=True),
        Binding("ctrl+q", "close", "Quit", priority=True),
        Binding("f6", "toggle_theme", "Theme"),
    ]

    class RuntimeEvent(UIMessage):
        def __init__(self, event):
            super().__init__()
            self.event = event

    def __init__(
        self,
        sessions: InteractiveSessions,
        *,
        session_id=None,
        sandbox_status="OS 沙箱不可用；使用权限审批",
    ):
        super().__init__()
        self._sessions = sessions
        self._initial_session_id = session_id
        self._sandbox_status = sandbox_status
        self.session: InteractiveSession | None = None
        self._render_failed = False
        self._pending_worker = None
        self._tree_nodes = {}
        self._tree_session = None
        self.session_id = None
        self.state = None
        self.ready = asyncio.Event()
        self.last_error = None
        self._memory_report = None
        self._operation = None
        self._closed = False
        self._live_text = ""
        self._live_thinking = ""
        self._model_choices = {}
        self._pending_plan = None
        self._displayed_entries = []
        self._displayed_session = None
        self._running_tools = {}
        for theme in themes():
            self.register_theme(theme)
        self.theme = "pycode-dark"

    @property
    def busy(self):
        return self._operation is not None and not self._operation.done()

    def compose(self) -> ComposeResult:
        yield Static("pycode", id="title", markup=False)
        with Horizontal(id="main"):
            with VerticalScroll(id="sidebar"):
                yield Button("New session", id="new-session", variant="primary")
                yield Input(placeholder="Session ID", id="session-id")
                yield Button("Open session", id="open-session")
                yield Select([], prompt="Saved sessions", id="saved-sessions")
                yield Select([], prompt="Provider / model", id="model-select")
                yield Button("Select model", id="choose-model")
                yield Tree("Session branches", id="session-tree")
                yield Button("Branch to selection", id="branch-selection")
                yield Select(
                    [("Dialogue", "dialogue"), ("Code", "code"), ("Both", "both")],
                    value="dialogue",
                    allow_blank=False,
                    id="revert-mode",
                )
                yield Button("Preview revert", id="preview-revert")
            with Vertical():
                yield VerticalScroll(id="conversation")
                yield Button("New messages · Back to bottom", id="back-to-bottom")
                yield Static("", id="live-thinking", markup=False)
                yield Static("", id="live-text", markup=False)
                yield Vertical(id="pending")
        with Vertical(id="entry"):
            yield ChatInput(id="chat-input", disabled=True)
            with Horizontal(id="actions"):
                yield Button("Send", id="send", variant="primary")
                yield Select(
                    [("Steer", "steer"), ("Follow-up", "follow_up")],
                    value="steer",
                    allow_blank=False,
                    id="queue-mode",
                )
                yield Button("Resume", id="resume")
                yield Button("Stop", id="stop")
                yield Button("Compact", id="compact")
                yield Button("Usage", id="usage")
        yield Static("", id="notice", markup=False)
        yield Static(self._sandbox_status, id="status", markup=False)
        yield Footer()

    async def on_mount(self):
        try:
            self._sandbox_status = (await self._sessions.sandbox_status()).reason
            await self.switch_session(self._initial_session_id)
        except DomainError as error:
            self.last_error = error
            self.notice(str(error), "error")
        finally:
            self.ready.set()

    async def consume(self, event):
        # Summary tasks intentionally run in a clean Context. UI updates occur in
        # the App's message pump, never inside model/summary execution callbacks.
        self.post_message(self.RuntimeEvent(event))

    async def on_py_app_runtime_event(self, message):
        if self._render_failed:
            return
        try:
            await self.render_runtime_event(message)
        except Exception as error:
            self.render_failed(error)

    def render_failed(self, error):
        self._render_failed = True
        logging.getLogger("pycode.ui").error(
            "Disabled failed UI renderer: %s", error, exc_info=True
        )
        try:
            self.notice(
                "Display failed; saved history remains available. Reopen the session to refresh.",
                "error",
            )
        except Exception:
            logging.getLogger("pycode.ui").exception("Could not display renderer failure")

    async def render_runtime_event(self, message):
        event = message.event
        if self._closed or event.session_id != self.session_id:
            return
        if event.kind == "message_delta":
            self._live_text += event.data["text"]
            self.query_one("#live-text", Static).update(self._live_text)
        elif event.kind == "thinking_delta":
            self._live_thinking += event.data["text"]
            self.query_one("#live-thinking", Static).update(self._live_thinking)
        elif event.kind == "message_end":
            self._live_text = self._live_thinking = ""
            self.query_one("#live-text", Static).update("")
            self.query_one("#live-thinking", Static).update("")
            await self.render_history()
        elif event.kind == "tool_execution_start":
            self.notice("Running " + event.data["name"])
            identifier = event.data["call_id"]
            if identifier not in self._running_tools:
                card = Static(
                    "Running " + event.data["name"], markup=False, classes="message-usage"
                )
                self._running_tools[identifier] = card
                await self.query_one("#conversation", VerticalScroll).mount(card)
        elif event.kind == "tool_execution_update":
            card = self._running_tools.get(event.data.get("call_id"))
            if card is not None:
                card.update(event.data.get("text", "Tool is running"))
        elif event.kind == "tool_execution_end":
            card = self._running_tools.pop(event.data["call_id"], None)
            if card is not None:
                await card.remove()
        elif event.kind == "usage":
            self.notice(event.data["purpose"] + " · " + token_text(event.data))
        elif event.kind == "error":
            self.notice(event.data["message"], "error")
        elif event.kind == "summary":
            self.notice("Summary: " + event.data.get("status", ""))
        elif event.kind == "notification" and event.data.get("kind") == "hook":
            self.notice(
                event.data["message"],
                "error"
                if event.data.get("error_code") or event.data.get("success") is False
                else None,
            )
        elif event.kind == "result":
            group = event.data["usage"]["main_turn"]
            self.notice(
                event.data["outcome"] + " · main task · " + token_text(group["tokens"]),
                "success" if event.data["outcome"] == "completed" else "warning",
            )

    def notice(self, text, severity=None):
        widget = self.query_one("#notice", Static)
        widget.set_classes(severity or "")
        widget.update(text)

    async def switch_session(self, session_id=None):
        await self.stop_active()
        target = session_id or uuid4().hex
        opened = await self._sessions.open(target, create=session_id is None, consumer=self)
        try:
            if self.session is not None:
                await self.session.aclose()
        except BaseException:
            await opened.aclose()
            raise
        identity = (await opened.inspect())["snapshot"]["header"]["session_id"]
        self.session, self.session_id = opened, identity
        self._render_failed = False
        self._memory_report = None
        self._pending_plan = None
        self._running_tools = {}
        self._live_text = self._live_thinking = ""
        self.query_one("#live-text", Static).update("")
        self.query_one("#live-thinking", Static).update("")
        catalog = await self.session.model_list()
        self._model_choices = {
            str(index): (model.provider, model.model_id)
            for index, model in enumerate(catalog.models)
        }
        self.query_one("#model-select", Select).set_options(
            [
                (model.provider + "/" + model.model_id + " · " + model.availability, str(index))
                for index, model in enumerate(catalog.models)
            ]
        )
        await self.refresh_state()
        self.query_one("#title", Static).update("pycode · " + self.session_id)

    async def render_history(self):
        if self.session is None or self._closed:
            return
        state = await self.session.inspect(presentation=True)
        area = self.query_one("#conversation", VerticalScroll)
        history = state["history"]
        identifiers = [item["entry_id"] for item in history]
        at_bottom = area.is_vertical_scroll_end
        added = len(identifiers) > len(self._displayed_entries)
        prefix = identifiers[: len(self._displayed_entries)] == self._displayed_entries
        if self._displayed_session != self.session_id or not prefix:
            await area.remove_children()
            self._displayed_entries = []
            self._displayed_session = self.session_id
        for item in history[len(self._displayed_entries) :]:
            entry_id = item["entry_id"]
            if item["kind"] == "compaction":
                await area.mount(Static(item["text"], markup=False, classes="message-usage"))
                self._displayed_entries.append(entry_id)
                continue
            message = item["message"]
            await area.mount(Static(message["role"], classes="message-heading", markup=False))
            if message["tool_result"] is not None:
                await area.mount(
                    ToolOutputCard(entry_id, message["tool_result"], self.session.read_tool_result)
                )
            elif message["role"] == "assistant":
                await area.mount(Markdown(message["text"], id="message-" + entry_id))
            else:
                await area.mount(Static(message["text"], markup=False))
            for thinking in message["thinking"]:
                await area.mount(
                    Static("Thinking: " + thinking["text"], markup=False, classes="message-usage")
                )
            if message["usage"] is not None:
                usage = message["usage"]
                shown = token_text(usage)
                if usage["input_tokens"] and usage["cache_read_tokens"] is not None:
                    hit_rate = usage["cache_read_tokens"] / usage["input_tokens"]
                    shown += f" · cache hit {hit_rate * 100:.1f}%"
                await area.mount(Static(shown, markup=False, classes="message-usage"))
            self._displayed_entries.append(entry_id)
        if at_bottom:
            area.scroll_end(animate=False)
            self.query_one("#back-to-bottom", Button).display = False
        elif added:
            self.query_one("#back-to-bottom", Button).display = True

    async def refresh_state(self):
        if self.session is None or self._closed:
            return
        self.state = await self.session.inspect(presentation=True)
        if self._render_failed:
            return
        try:
            await self.refresh_widgets()
        except Exception as error:
            self.render_failed(error)

    async def refresh_widgets(self):
        await self.render_history()
        tree = self.query_one("#session-tree", Tree)
        if self._tree_session != self.session_id:
            tree.clear()
            self._tree_nodes = {}
            self._tree_session = self.session_id
        nodes = self._tree_nodes
        for entry in self.state["snapshot"]["entries"]:
            if entry["id"] in nodes:
                continue
            parent = nodes.get(entry["parent_id"], tree.root)
            label = (
                entry["type"]
                if entry["message"] is None
                else entry["message"]["role"] + ": " + entry["message"]["text"][:35]
            )
            nodes[entry["id"]] = parent.add(label, data=entry["id"], expand=True)
        await self.query_one("#pending", Vertical).remove_children()
        pending = self.state["pending"]
        self._pending_worker = None
        if not pending:
            for identifier, task in self.state.get("team", {}).items():
                if task.get("pending"):
                    self._pending_worker = identifier
                    pending = task["pending"]
                    break
        if pending:
            payload = pending[0]
            if payload["kind"] == "permission":
                await self.query_one("#pending", Vertical).mount(
                    ApprovalPanel(
                        payload["operation_id"],
                        payload["call"]["name"],
                        json_dumps(
                            {
                                "arguments": payload["call"]["arguments"],
                                "details": payload.get("approval_details", {}),
                                "sandbox": payload.get("sandbox"),
                                "worker": self._pending_worker,
                            },
                            ensure_ascii=False,
                            indent=2,
                        ),
                    )
                )
            elif payload["kind"] == "plan":
                await self.query_one("#pending", Vertical).mount(PlanPanel(payload))
            elif payload["kind"] == "ask_user":
                await self.query_one("#pending", Vertical).mount(QuestionPanel(payload))
            elif payload["kind"] == "mcp_elicitation":
                await self.query_one("#pending", Vertical).mount(MCPElicitationPanel(payload))
            else:
                self.notice(
                    "Pending operation review; use /resume or explicit reconciliation", "warning"
                )
        if self.state["pending_revert_id"]:
            self.notice("Revert recovery required: " + self.state["pending_revert_id"], "warning")
            await self.query_one("#pending", Vertical).mount(
                RevertPanel(transaction_id=self.state["pending_revert_id"])
            )
        elif self._pending_plan is not None:
            await self.query_one("#pending", Vertical).mount(RevertPanel(self._pending_plan))
        elif not pending and self._memory_report is not None:
            await self.query_one("#pending", Vertical).mount(MemoryPanel(self._memory_report))
        self.query_one("#chat-input", ChatInput).disabled = bool(
            self.state["pending_run_id"] or self.state["pending_revert_id"]
        )
        self.query_one("#resume", Button).disabled = not bool(self.state["pending_run_id"])
        sessions = await self._sessions.list_sessions()
        self.query_one("#saved-sessions", Select).set_options(
            [
                (
                    item["title"] + " · " + item["updated_at"] + " · " + item["short_id"],
                    item["session_id"],
                )
                for item in sessions
            ]
        )
        selected = self.state["context"]["selection"]
        label = (
            selected["provider"] + "/" + selected["model_id"]
            if selected
            else "Choose a model or start a task"
        )
        self.query_one("#status", Static).update(
            label + " · " + context_text(self.state["context_usage"]) + " · " + self._sandbox_status
        )
        if not pending and not self.query_one("#chat-input", ChatInput).disabled:
            self.query_one("#chat-input", ChatInput).focus()

    def start_operation(self, function, *args):
        if self.busy:
            self.notice("Wait for the current command or stop it first", "warning")
            return
        self.last_error = None
        self._operation = asyncio.create_task(
            self.execute_operation(function, args), name="pycode-ui-operation"
        )

    async def execute_operation(self, function, args):
        try:
            value = await function(*args)
            if isinstance(value, RunResult):
                await self.session.publish_result(value)
        except asyncio.CancelledError:
            if not self._closed:
                self.notice("Stopped; execution remains recoverable", "warning")
        except DomainError as error:
            self.last_error = error
            self.notice(str(error), "error")
        finally:
            if not self._closed:
                await self.refresh_state()

    async def wait_idle(self):
        if self._operation is not None:
            await asyncio.shield(self._operation)

    async def send_text(self, text):
        if self.session is None:
            return
        if text.startswith("/"):
            await self.command(text)
        elif self.busy:
            kind = self.query_one("#queue-mode", Select).value
            await self.session.submit_input(Message(uuid4().hex, "user", text), kind)
            self.notice("Queued " + kind)
        else:
            self.start_operation(self.session.run, Message(uuid4().hex, "user", text))

    async def on_chat_input_submitted(self, event):
        try:
            await self.send_text(event.text)
        except DomainError as error:
            self.notice(str(error), "error")

    def on_approval_panel_responded(self, event):
        event.stop()
        self.query_one("#perm-inline", ApprovalPanel).disabled = True
        self.respond(event.answer)

    def respond(self, answer):
        if self._pending_worker is not None:
            self.start_operation(self.session.team_answer, self._pending_worker, answer)
        else:
            self.start_operation(self.session.resume, answer)

    def on_plan_panel_responded(self, event):
        self.respond(event.answer)

    def on_mcpelicitation_panel_responded(self, event):
        event.stop()
        self.query_one("#mcp-elicitation-inline", MCPElicitationPanel).disabled = True
        self.respond(event.answer)

    def on_question_panel_responded(self, event):
        event.stop()
        self.query_one("#askuser-inline", QuestionPanel).disabled = True
        self.respond(event.answer)

    def on_revert_panel_responded(self, event):
        event.stop()
        if event.action == "revert-discard":
            self._pending_plan = None
            self.start_operation(self.refresh_state)
        elif event.action == "revert-apply":
            self.start_operation(self.apply_revert)
        elif event.action in {"restore-continue", "restore-compensate"}:
            self.start_operation(self.recover_revert, event.action == "restore-compensate")

    async def preview_revert(self, target, mode):
        self._pending_plan = await self.session.preview_revert(target, mode)

    async def apply_revert(self):
        await self.session.revert(self._pending_plan)
        self._pending_plan = None

    async def recover_revert(self, compensate):
        await self.session.recover_revert(self.state["pending_revert_id"], compensate=compensate)
        self._pending_plan = None

    async def stop_active(self):
        if self.busy:
            await self.session.cancel()
            if self.busy:
                self._operation.cancel()
            await self.wait_idle()
        elif self.session is not None:
            await self.session.cancel()

    async def action_stop(self):
        await self.stop_active()

    async def action_close(self):
        await self.shutdown()
        self.exit()

    def action_toggle_theme(self):
        self.theme = "pycode-light" if self.theme == "pycode-dark" else "pycode-dark"

    async def shutdown(self):
        if self._closed:
            return
        self._closed = True
        await self.stop_active()
        if self.session is not None:
            await self.session.aclose()

    async def on_unmount(self):
        await self.shutdown()

    async def on_button_pressed(self, event):
        if event.button.id == "back-to-bottom":
            self.query_one("#conversation", VerticalScroll).scroll_end(animate=False)
            event.button.display = False
            return
        try:
            name = event.button.id
            if name == "send":
                self.query_one("#chat-input", ChatInput).action_submit()
            elif name == "new-session":
                await self.switch_session()
            elif name == "open-session":
                value = (
                    self.query_one("#session-id", Input).value
                    or self.query_one("#saved-sessions", Select).value
                )
                if isinstance(value, str):
                    await self.switch_session(value)
            elif name == "choose-model":
                choice = self._model_choices.get(self.query_one("#model-select", Select).value)
                if choice:
                    self.start_operation(self.session.select_model, *choice)
            elif name == "branch-selection":
                node = self.query_one("#session-tree", Tree).cursor_node
                if node is not None:
                    self.start_operation(self.session.branch, node.data)
            elif name == "resume":
                self.start_operation(self.session.resume)
            elif name == "preview-revert":
                node = self.query_one("#session-tree", Tree).cursor_node
                if node is not None:
                    self.start_operation(
                        self.preview_revert, node.data, self.query_one("#revert-mode", Select).value
                    )
            elif name == "stop":
                await self.stop_active()
            elif name == "compact":
                self.start_operation(self.session.compact)
            elif name == "usage":
                self.push_screen(UsageScreen(await self.session.usage()))
        except DomainError as error:
            self.notice(str(error), "error")

    async def command(self, text):
        if text.startswith("/skill:"):
            command, _, arguments = text.partition(" ")
            self.start_operation(self.load_skill, command[len("/skill:") :], arguments)
            return
        try:
            parts = shlex.split(text)
        except ValueError as error:
            raise StorageError("invalid_command", str(error)) from error
        name, args = parts[0], parts[1:]
        if name in {"/clear", "/new"} or (name == "/session" and args == ["new"]):
            await self.switch_session()
        elif name == "/session" and len(args) == 2 and args[0] == "open":
            await self.switch_session(args[1])
        elif name == "/session" and args == ["list"]:
            self.notice(json_dumps(await self._sessions.list_sessions(), ensure_ascii=False))
        elif name == "/session" and len(args) >= 2 and args[0] == "name":
            self.start_operation(self.session.name, " ".join(args[1:]))
        elif name == "/session" and len(args) == 2 and args[0] == "fork":
            await self._sessions.fork(self.session_id, self.state["snapshot"]["leaf_id"], args[1])
            await self.switch_session(args[1])
        elif name == "/resume" and not args:
            self.start_operation(self.session.resume)
        elif name == "/model" and len(args) == 2:
            self.start_operation(self.session.select_model, *args)
        elif name == "/memory" and not args:
            self.start_operation(self.show_memory)
        elif name == "/memory" and len(args) == 2 and args[0] == "mode":
            self.start_operation(self.session.memory_mode, args[1])
        elif name == "/memory" and len(args) == 3 and args[0] == "review":
            self.start_operation(self.session.memory_review, args[1], args[2])
        elif name == "/memory" and len(args) >= 3 and args[0] == "edit":
            self.start_operation(self.session.memory_edit, args[1], " ".join(args[2:]))
        elif name == "/memory" and len(args) == 2 and args[0] == "remove":
            self.start_operation(self.session.memory_remove, args[1])
        elif name == "/memory" and args == ["clear"]:
            self.start_operation(self.session.memory_clear)
        elif name == "/memory" and args == ["extract"]:
            self.start_operation(self.session.memory_extract)
        elif name == "/storage" and args == ["status"]:
            self.start_operation(self.show_storage)
        elif name == "/storage" and args in (["gc"], ["gc", "--dry-run"]):
            self.start_operation(self.collect_storage, "--dry-run" in args)
        elif name == "/plan":
            self.start_operation(self.enter_plan, " ".join(args))
        elif name == "/usage":
            self.push_screen(UsageScreen(await self.session.usage()))
        elif name == "/team" and args in ([], ["list"]):
            self.start_operation(self.team_list)
        elif name == "/team" and len(args) == 2 and args[0] in {"check", "cancel"}:
            self.start_operation(self.team_action, args[0] + "_async_task", {"task_id": args[1]})
        elif name == "/team" and len(args) >= 3 and args[0] == "update":
            self.start_operation(
                self.team_action,
                "update_async_task",
                {"task_id": args[1], "message": " ".join(args[2:])},
            )
        elif name == "/compact" and not args:
            self.start_operation(self.session.compact)
        elif name == "/reload" and not args:
            self.start_operation(self.reload_skills)
        elif name == "/skills":
            self.start_operation(self.search_skills, " ".join(args))
        elif name == "/hooks" and len(args) >= 2 and args[1] in {"result", "retry", "abandon"}:
            self.start_operation(self.session.reconcile_hook, args[0], args[1], " ".join(args[2:]))
        elif name == "/skill" and len(args) == 2 and args[0] == "install":
            self.start_operation(self.install_skill, args[1])
        elif name == "/branch" and len(args) == 1:
            self.start_operation(self.session.branch, None if args[0] == "root" else args[0])
        elif name == "/theme":
            self.action_toggle_theme()
        elif name == "/revert" and 1 <= len(args) <= 2:
            self.start_operation(
                self.preview_revert,
                None if args[0] == "root" else args[0],
                args[1] if len(args) == 2 else "dialogue",
            )
        elif name in {"/quit", "/exit"}:
            await self.action_close()
        else:
            self.notice(
                "Commands: /session new|list|open ID|fork ID, /resume, "
                "/model PROVIDER MODEL, /usage, /compact, /branch ENTRY, /theme, "
                "/skills QUERY, /skill:NAME ARGUMENTS, /skill install URL, /reload",
                "warning",
            )

    async def show_memory(self):
        self._memory_report = await self.session.memory_list()

    async def team_list(self):
        result = await self.session.team_control("list_async_tasks", {"status_filter": "all"})
        self.notice(result)

    async def team_action(self, name, arguments):
        self.notice(await self.session.team_control(name, arguments))

    def on_memory_panel_reviewed(self, event):
        event.stop()
        self.start_operation(
            self.review_memory, event.candidate_id, event.decision, event.content, event.scope
        )

    async def review_memory(self, candidate_id, decision, content, scope):
        await self.session.memory_review(candidate_id, decision, content=content, scope=scope)
        self._memory_report = await self.session.memory_list()
        self.notice("Memory review saved")

    async def show_storage(self):
        report = await self.session.storage_status()
        self.notice(json_dumps(report, ensure_ascii=False))

    async def collect_storage(self, dry_run):
        report = await self.session.storage_gc(dry_run=dry_run)
        self.notice(json_dumps(report, ensure_ascii=False))

    async def enter_plan(self, task):
        result = await self.session.enter_plan(task)
        self.notice("Plan mode entered")
        return result

    async def load_skill(self, name, arguments):
        result = await self.session.load_skill(name, arguments)
        self.notice("Skill task: " + name)
        return result

    async def reload_skills(self):
        catalog = await self.session.reload_skills()
        self.notice(f"Skill catalog refreshed: {len(catalog['entries'])} entries")

    async def search_skills(self, query):
        found = await self.session.skill_search(query)
        self.notice("\n".join(item["name"] + ": " + item["description"] for item in found))

    async def install_skill(self, url):
        report = await self.session.install_skill(url)
        self.notice("Installed Skill " + report["skill_name"] + " at " + report["target_dir"])
