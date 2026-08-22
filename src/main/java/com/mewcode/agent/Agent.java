package com.mewcode.agent;

import com.mewcode.config.ProviderConfig;
import com.mewcode.conversation.ConversationManager;
import com.mewcode.conversation.ThinkingBlock;
import com.mewcode.conversation.ToolResultBlock;
import com.mewcode.conversation.ToolUseBlock;
import com.mewcode.hook.HookEngine;
import com.mewcode.llm.LlmClient;
import com.mewcode.llm.LlmException;
import com.mewcode.llm.StreamEvent;
import com.mewcode.permission.PermissionChecker;
import com.mewcode.permission.PermissionMode;
import com.mewcode.plan.PlanFile;
import com.mewcode.prompt.PlanModePrompt;
import com.mewcode.tool.ToolRegistry;
import com.mewcode.toolresult.ContentReplacementRecord;
import com.mewcode.toolresult.ContentReplacementState;
import com.mewcode.toolresult.ReplacementRecordsIO;
import com.mewcode.toolresult.ToolResultBudget;

import java.nio.file.Path;
import java.nio.file.Paths;

import java.util.*;
import java.util.concurrent.*;

public class Agent {

    private static final int MAX_TOKENS_CEILING = 64_000;
    private static final int MAX_OUTPUT_RECOVERIES = 3;

    private final LlmClient client;
    private final ToolRegistry registry;
    private final String protocol;
    private final int contextWindow;
    private final int maxOutput;
    private PermissionChecker checker;
    private HookEngine hookEngine;
    private int maxIterations;
    private String workDir;
    /**
     * Session log id for the on-disk transcript. Plumbed so that an in-loop
     * compaction can append a compact_boundary record into the same session file
     * (enabling resume to rebuild the compacted state). Null for sub-agents /
     * one-shot callers that should not write boundaries into the main session.
     */
    private String sessionId;
    private java.util.function.Supplier<List<String>> notificationFn;

    private java.util.function.Predicate<String> toolNameFilter;
    private String instructions = "";
    private String memoryContent = "";

    /**
     * Conversation-scoped state shared by every iteration: conversation,
     * compaction anchors, recovery data, tool-result decisions, retries,
     * usage, and asynchronous memory recall.
     */
    private final AgentLoopState loopState = new AgentLoopState();

    /**
     * Per-conversation-thread tool-result decision log. Carries across
     * iterations so Anthropic's prompt cache sees byte-stable prefixes.
     * Forks (see {@code AgentTool}) clone this for their child agent.
     */
    public ContentReplacementState getReplacementState() { return loopState.replacementState; }
    public void setReplacementState(ContentReplacementState state) { loopState.replacementState = state; }

    /**
     * Holds the snapshots needed to rebuild working context after Layer 2
     * collapses the conversation: most-recent file reads + skill SOPs.
     * Recorded on each ReadFile / skill call; consumed by ContextCompactor
     * when the threshold trips.
     */
    public com.mewcode.compact.RecoveryState getRecoveryState() { return loopState.recoveryState; }

    private com.mewcode.filehistory.FileHistory fileHistory;
    public void setFileHistory(com.mewcode.filehistory.FileHistory fh) { this.fileHistory = fh; }
    public com.mewcode.filehistory.FileHistory getFileHistory() { return fileHistory; }

    public ToolRegistry getRegistry() { return registry; }
    public String getProtocol() { return protocol; }

    public Agent(LlmClient client, ToolRegistry registry, String protocol, ProviderConfig cfg) {
        this.client = client;
        this.registry = registry;
        this.protocol = protocol;
        this.contextWindow = cfg.resolvedContextWindow();
        this.maxOutput = cfg.resolvedMaxOutputTokens();
    }

    public void setChecker(PermissionChecker checker) { this.checker = checker; }
    public void setHookEngine(HookEngine hookEngine) { this.hookEngine = hookEngine; }
    public void setMaxIterations(int max) { this.maxIterations = max; }
    public void setWorkDir(String workDir) { this.workDir = workDir; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getSessionId() { return sessionId; }
    public void setNotificationFn(java.util.function.Supplier<List<String>> fn) { this.notificationFn = fn; }

    public void setToolNameFilter(java.util.function.Predicate<String> filter) { this.toolNameFilter = filter; }
    public void setInstructions(String instructions) { this.instructions = instructions; }
    public void setMemoryContent(String memoryContent) { this.memoryContent = memoryContent; }
    public void setMemoryRecallFuture(CompletableFuture<String> future) {
        loopState.setMemoryRecallFuture(future);
    }
    public void setKnowledgeRecallFuture(CompletableFuture<String> future) {
        loopState.setKnowledgeRecallFuture(future);
    }
    public HookEngine getHookEngine() { return hookEngine; }

    public BlockingQueue<AgentEvent> run(ConversationManager conv) {
        var queue = new LinkedBlockingQueue<AgentEvent>(64);
        run(conv, queue);
        return queue;
    }

    // 使用调用方提供的 queue，允许 TUI 预先创建 queue 立即开始轮询
    public void run(ConversationManager conv, BlockingQueue<AgentEvent> queue) {
        Thread.startVirtualThread(() -> {
            try {
                loopState.beginRun(conv);
                agentLoop(loopState, queue);
            } catch (Exception e) {
                putSafe(queue, new AgentEvent.ErrorEvent("Agent error: " + e.getMessage()));
            }
        });
    }

    private void agentLoop(AgentLoopState state, BlockingQueue<AgentEvent> queue) {
        ConversationManager conv = state.conversation;
        ensureLongTermMemory(conv);
        if (workDir != null) {
            var activePlan = new com.mewcode.plan.PlanRepository(java.nio.file.Path.of(workDir)).load();
            activePlan.filter(p -> p.status() == com.mewcode.plan.PlanState.Status.ACTIVE)
                    .ifPresent(p -> conv.addSystemReminder("Active structured plan:\n" +
                            new com.mewcode.plan.PlanCoordinator(new com.mewcode.plan.PlanRepository(java.nio.file.Path.of(workDir))).status() +
                            "\nUse CompletePlanStep or FailPlanStep to record each outcome."));
        }

        try {
        while (!state.loopCompleted) {
            state.iteration++;
            if (maxIterations > 0 && state.iteration > maxIterations) {
                putSafe(queue, new AgentEvent.ErrorEvent(
                        "Agent reached maximum iterations (%d)".formatted(maxIterations)));
                break;
            }

            if (Thread.currentThread().isInterrupted()) break;

            if (!state.memoryRecallConsumed) {
                state.memoryRecallConsumed = injectRecallIfReady(state.memoryRecallFuture, conv, 0);
            }
            if (!state.knowledgeRecallConsumed) {
                state.knowledgeRecallConsumed = injectRecallIfReady(
                        state.knowledgeRecallFuture, conv, state.iteration == 1 ? 300 : 0);
            }

            // Drain background task notifications and inject as system reminders
            if (notificationFn != null) {
                for (String note : notificationFn.get()) {
                    conv.addSystemReminder(note);
                }
            }

            // Compute the tool schemas once per iteration so the recovery
            // attachment (when compact fires) and the Stream call below see
            // the same set. Skill filters can only change between iterations.
            var iterToolSchemas = registry.getAllSchemas(protocol);
            if (toolNameFilter != null) {
                iterToolSchemas = iterToolSchemas.stream()
                        .filter(schema -> {
                            Object name = schema.get("name");
                            return name == null || toolNameFilter.test(name.toString());
                        })
                        .toList();
            }

            // Inject deferred tool names as system reminder
            var deferredNames = registry.getDeferredToolNames();
            if (!deferredNames.isEmpty()) {
                var sb = new StringBuilder();
                sb.append("The following deferred tools are available via ToolSearch. ");
                sb.append("Their schemas are NOT loaded - use ToolSearch with ");
                sb.append("query \"select:<name>[,<name>...]\" to load tool schemas before calling them:\n");
                for (var dn : deferredNames) {
                    sb.append(dn).append("\n");
                }
                conv.addSystemReminder(sb.toString());
            }

            // Plan mode: inject structured workflow reminder
            if (checker != null && checker.getMode() == PermissionMode.PLAN) {
                String wd = workDir != null ? workDir : System.getProperty("user.dir");
                String planPath = PlanFile.getOrCreatePlanPath(wd);
                checker.setPlanFilePath(planPath);
                boolean planExists = PlanFile.planExists();
                String reminder = PlanModePrompt.buildReminder(planPath, planExists, state.iteration);
                conv.addSystemReminder(reminder);
            }

            // Layer 1: apply tool-result budget（就地修改 conv，Design A）
            Path sessionDir = Paths.get(workDir == null ? "." : workDir, ".mewcode/session");
            List<ContentReplacementRecord> newRecords = ToolResultBudget.apply(conv, sessionDir, state.replacementState);
            if (!newRecords.isEmpty()) {
                try {
                    ReplacementRecordsIO.append(sessionDir, newRecords);
                } catch (Exception ignored) {}
            }

            // Layer 2: auto-compact check
            // 用 Layer 1 就地裁剪后的 conv 消息估算 token，判断更精确
            try {
                String wd = workDir != null ? workDir : System.getProperty("user.dir");
                int sizeBefore = conv.size();
                String compactMsg = com.mewcode.compact.ContextCompactor.manage(
                        conv, client, contextWindow, maxOutput, wd, sessionId, state.compactTracking,
                        state.recoveryState, iterToolSchemas, state.usageAnchor,
                        conv.getMessages());
                if (compactMsg != null && !compactMsg.isEmpty()) {
                    putSafe(queue, new AgentEvent.CompactEvent(compactMsg));
                }
                // 压缩把旧消息替换成摘要，旧锚点失效，下次 stream 重新锚定
                if (conv.size() < sizeBefore) {
                    state.usageAnchor = null;
                    ensureLongTermMemory(conv);
                    // 压缩后 conv 已变，重新应用 tool-result budget
                    newRecords = ToolResultBudget.apply(conv, sessionDir, state.replacementState);
                }
            } catch (Exception ignored) {}

            var tools = iterToolSchemas;
            long responseStartedAt = System.nanoTime();
            var streamQueue = client.stream(conv, tools);
            var streamingAssistant = new com.mewcode.conversation.Message("assistant", "");
            streamingAssistant.setStatus(com.mewcode.conversation.MessageStatus.STREAMING);
            // Add only after client.stream captured its immutable request snapshot, so the empty
            // placeholder is visible to the local lifecycle but never sent to the provider.
            conv.getMessagesMutable().add(streamingAssistant);
            state.lastStreamException = null;

            // Consume stream events, collect tool calls
            var text = new StringBuilder();
            var thinkingBlocks = new ArrayList<ThinkingBlock>();
            var toolCalls = new ArrayList<ToolCallInfo>();
            String stopReason = "end_turn";
            int turnInput = 0, turnOutput = 0;
            int turnCacheRead = 0, turnCacheCreation = 0;
            boolean streamError = false;

            while (true) {
                StreamEvent event;
                try {
                    event = streamQueue.poll(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }

                if (event == null) {
                    putSafe(queue, new AgentEvent.ErrorEvent("Stream timeout"));
                    return;
                }

                switch (event) {
                    case StreamEvent.TextDelta td -> {
                        text.append(td.text());
                        putSafe(queue, new AgentEvent.StreamText(td.text()));
                    }
                    case StreamEvent.ThinkingDelta td ->
                            putSafe(queue, new AgentEvent.ThinkingText(td.text()));
                    case StreamEvent.ThinkingComplete tc -> {
                        thinkingBlocks.add(new ThinkingBlock(tc.thinking(), tc.signature()));
                        putSafe(queue, new AgentEvent.ThinkingComplete(tc.thinking(), tc.signature()));
                    }
                    case StreamEvent.ToolCallStart tcs ->
                            putSafe(queue, new AgentEvent.ToolUseEvent(tcs.toolId(), tcs.toolName(), Map.of()));
                    case StreamEvent.ToolCallDelta tcd -> {}
                    case StreamEvent.ToolCallComplete tcc -> {
                        toolCalls.add(new ToolCallInfo(tcc.toolId(), tcc.toolName(), tcc.arguments()));
                        putSafe(queue, new AgentEvent.ToolUseEvent(
                                tcc.toolId(), tcc.toolName(), tcc.arguments()));
                    }
                    case StreamEvent.StreamEnd se -> {
                        stopReason = se.stopReason();
                        turnInput = se.inputTokens();
                        turnOutput = se.outputTokens();
                        turnCacheRead = se.cacheReadTokens();
                        turnCacheCreation = se.cacheCreationTokens();
                    }
                    case StreamEvent.Error err -> {
                        state.lastStreamException = err.exception();
                        putSafe(queue, new AgentEvent.ErrorEvent(err.message()));
                        streamError = true;
                    }
                }

                if (event instanceof StreamEvent.StreamEnd || event instanceof StreamEvent.Error) break;
            }

            // Error recovery: branch on the typed LLM failure instead of
            // parsing human-readable provider messages.
            if (streamError) {
                streamingAssistant.setStatus(com.mewcode.conversation.MessageStatus.ERROR);
                var error = state.lastStreamException;

                if (error instanceof LlmException.ContextTooLongException) {
                    if (state.contextRetries < 3) {
                        state.contextRetries++;
                        putSafe(queue, new AgentEvent.RetryEvent(
                                "Context too long, compacting...", 0));
                        // Apply the result budget before compacting the context.
                        Path forceSessionDir = Paths.get(
                                workDir == null ? "." : workDir,
                                ".mewcode/session");
                        List<ContentReplacementRecord> forceRecords =
                                ToolResultBudget.apply(
                                        conv, forceSessionDir, state.replacementState);
                        if (!forceRecords.isEmpty()) {
                            try {
                                ReplacementRecordsIO.append(forceSessionDir, forceRecords);
                            } catch (Exception ignored) {
                            }
                        }
                        int sizeBeforeForce = conv.size();
                        try {
                            String wdForce = workDir != null
                                    ? workDir
                                    : System.getProperty("user.dir");
                            com.mewcode.compact.ContextCompactor.forceCompact(
                                    conv, client, contextWindow, wdForce, sessionId,
                                    state.recoveryState, iterToolSchemas,
                                    conv.getMessages());
                        } catch (Exception ignored) {
                        }
                        // forceCompact shrinks the conversation (summary + kept
                        // tail), so the prior anchor's message count no longer
                        // lines up; drop it and re-anchor on the next stream.
                        if (conv.size() < sizeBeforeForce) {
                            state.usageAnchor = null;
                            ensureLongTermMemory(conv);
                        }
                        continue;
                    }
                }

                if (error instanceof LlmException.RateLimitException rateLimit
                        && state.rateLimitRetries < 3) {
                    state.rateLimitRetries++;
                    long waitMs = retryDelayMillis(
                            rateLimit.getRetryAfter(), state.rateLimitRetries);
                    putSafe(queue, new AgentEvent.RetryEvent(
                            "Rate limited, retrying...", waitMs));
                    try {
                        Thread.sleep(waitMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    continue;
                }

                // Authentication and unknown errors are not safe to retry.
                break;
            }

            // A successful stream resets the bounded rate-limit retry budget.
            state.rateLimitRetries = 0;
            state.totalInputTokens += turnInput;
            state.totalOutputTokens += turnOutput;
            putSafe(queue, new AgentEvent.UsageEvent(state.totalInputTokens, state.totalOutputTokens));

            var toolUseBlocks = toolCalls.stream()
                    .map(tc -> new ToolUseBlock(tc.toolId, tc.toolName, tc.args))
                    .toList();
            streamingAssistant.setContent(text.toString());
            streamingAssistant.setThinkingBlocks(thinkingBlocks);
            streamingAssistant.setToolUses(toolUseBlocks);
            streamingAssistant.setUsage(new com.mewcode.conversation.UsageInfo(
                    turnInput, turnOutput, turnCacheRead, turnCacheCreation));
            streamingAssistant.setResponseTimeMs(
                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - responseStartedAt));
            streamingAssistant.setStatus(com.mewcode.conversation.MessageStatus.COMPLETE);

            // Max tokens handling
            if ("max_tokens".equals(stopReason)) {
                // A truncated response never reaches tool execution. Do not leave tool_use blocks
                // in history without matching results before asking the provider to continue.
                streamingAssistant.setToolUses(List.of());
                if (!state.maxTokensEscalated) {
                    state.maxTokensEscalated = true;
                    client.setMaxOutputTokens(MAX_TOKENS_CEILING);
                    if (!text.isEmpty()) {
                        conv.addUserMessage("Output token limit hit. Resume directly from where you stopped. Do not apologize or repeat previous content. Pick up mid-thought if needed.");
                    }
                    putSafe(queue, new AgentEvent.RetryEvent("max_tokens escalation", 0));
                    continue;
                } else if (state.outputRecoveries < MAX_OUTPUT_RECOVERIES) {
                    state.outputRecoveries++;
                    conv.addUserMessage("Output token limit hit. Resume directly from where you stopped. Break remaining work into smaller pieces.");
                    putSafe(queue, new AgentEvent.RetryEvent(
                            "max_tokens recovery %d/%d".formatted(state.outputRecoveries, MAX_OUTPUT_RECOVERIES), 0));
                    continue;
                }
                // Exhausted: fall through to normal completion
            } else {
                state.outputRecoveries = 0;
            }

            // Save assistant message to conversation
            // The placeholder created above is now the completed assistant turn. Keeping the same
            // object gives UI/state consumers a stable message ID throughout streaming.

            // Re-anchor the compaction estimate on this turn's real usage. The
            // baseline = input + cacheRead + cacheCreation + output covers the
            // sent context and the assistant message just appended; messages
            // added after this point (tool results, next user turn) are
            // estimated incrementally on top. A cache hit reports a small real
            // input, so the anchor tracks the true window far better than the
            // raw character estimate.
            if (turnInput > 0 || turnOutput > 0 || turnCacheRead > 0 || turnCacheCreation > 0) {
                int baseline = turnInput + turnCacheRead + turnCacheCreation + turnOutput;
                state.usageAnchor = new com.mewcode.compact.ContextCompactor.UsageAnchor(
                        baseline, conv.size());
            }

            // No tool calls → done
            if (toolCalls.isEmpty()) {
                if (fileHistory != null) {
                    String summary = text.length() > 60 ? text.substring(0, 60) + "..." : text.toString();
                    fileHistory.makeSnapshot(conv.size(), summary);
                }
                // No TurnComplete on the terminal (no-tool) turn — aligning Go
                // (agent.go emits only LoopComplete here). The TUI's TurnComplete
                // handler flushes+clears streamBuf without persisting; if we emitted
                // it first, LoopComplete would see an empty buffer and the final
                // assistant message would never be saved to the session file.
                putSafe(queue, new AgentEvent.LoopComplete(state.iteration));
                state.loopCompleted = true;
                break;
            }

            // Execute tool calls
            var executor = new StreamingExecutor(registry, checker, hookEngine, queue, state.recoveryState,
                    java.nio.file.Path.of(workDir == null ? "." : workDir), sessionId);
            var callInfos = toolCalls.stream()
                    .map(tc -> new StreamingExecutor.ToolCallInfo(tc.toolId, tc.toolName, tc.args))
                    .toList();
            var results = executor.executeAll(callInfos);

            // Add results to conversation
            var resultBlocks = results.stream()
                    .map(r -> new ToolResultBlock(r.toolId(), r.output(), r.isError()))
                    .toList();
            conv.addToolResultsMessage(resultBlocks);

            // 非阻塞 memory recall：工具执行完后检查 prefetch 是否就绪
            // 记忆在第 1 轮工具执行后、第 2 轮迭代前注入
            if (!state.memoryRecallConsumed) {
                state.memoryRecallConsumed = injectRecallIfReady(state.memoryRecallFuture, conv, 0);
            }
            if (!state.knowledgeRecallConsumed) {
                state.knowledgeRecallConsumed = injectRecallIfReady(state.knowledgeRecallFuture, conv, 0);
            }

            boolean exitPlanSucceeded = results.stream().anyMatch(result -> !result.isError()
                    && toolCalls.stream().anyMatch(call -> "ExitPlanMode".equals(call.toolName)
                    && call.toolId.equals(result.toolId())));
            if (exitPlanSucceeded) {
                putSafe(queue, new AgentEvent.TurnComplete(state.iteration));
                putSafe(queue, new AgentEvent.LoopComplete(state.iteration));
                state.loopCompleted = true;
                break;
            }

            putSafe(queue, new AgentEvent.TurnComplete(state.iteration));
        }
        } finally {
            if (!state.loopCompleted) {
                putSafe(queue, new AgentEvent.LoopComplete(0));
            }
        }
    }

    private static boolean injectRecallIfReady(
            CompletableFuture<String> future, ConversationManager conversation, long waitMillis) {
        if (future == null) return true;
        try {
            String reminder = waitMillis > 0
                    ? future.get(waitMillis, TimeUnit.MILLISECONDS)
                    : future.getNow(null);
            if (reminder == null) return false;
            if (!reminder.isBlank()) conversation.addSystemReminder(reminder);
            return true;
        } catch (java.util.concurrent.TimeoutException ignored) {
            return false;
        } catch (Exception ignored) {
            return true;
        }
    }

    /**
     * Compaction preserves the pinned long-term reminder. The fallback handles
     * conversations rebuilt by an older or external compaction path.
     */
    private void ensureLongTermMemory(ConversationManager conv) {
        if (conv.hasLongTermMemory()) return;
        conv.resetLtmInjected();
        conv.injectLongTermMemory(instructions, memoryContent);
    }

    private static long retryDelayMillis(String retryAfter, int attempt) {
        if (retryAfter != null && !retryAfter.isBlank()) {
            try {
                long seconds = Long.parseLong(retryAfter.trim());
                return Math.max(0L, Math.min(seconds * 1000L, 120_000L));
            } catch (NumberFormatException ignored) {
                // Fall through to bounded exponential backoff.
            }
        }
        long backoff = 1_000L << Math.min(Math.max(attempt - 1, 0), 6);
        return Math.min(backoff, 120_000L);
    }

    private static void putSafe(BlockingQueue<AgentEvent> queue, AgentEvent event) {
        try {
            queue.put(event);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record ToolCallInfo(String toolId, String toolName, Map<String, Object> args) {}
    private record ToolCallResult(String toolId, String output, boolean isError) {}
}
