// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.agent;

import com.mewcode.compact.RecoveryState;
import com.mewcode.hook.HookEngine;
import com.mewcode.permission.PermissionChecker;
import com.mewcode.permission.PermissionResponse;
import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolCategory;
import com.mewcode.tool.ToolRegistry;
import com.mewcode.tool.ToolResult;
import com.mewcode.tool.ToolExecutionRecord;
import com.mewcode.tool.JsonlToolExecutionLedger;
import com.mewcode.tool.MutationPreview;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Concurrent tool executor that partitions tool calls into read-only (parallel)
 * and write/command (sequential) batches.
 */
public class StreamingExecutor {

    private final ToolRegistry registry;
    private final PermissionChecker checker;

    private final HookEngine hookEngine;
    private final BlockingQueue<AgentEvent> eventQueue;
    private final RecoveryState recoveryState;
    private final JsonlToolExecutionLedger ledger;
    private final String runId;

    public record ToolCallInfo(String toolId, String toolName, Map<String, Object> args) {}
    public record ToolExecResult(String toolId, String output, boolean isError) {}

    public StreamingExecutor(ToolRegistry registry, PermissionChecker checker,
                             HookEngine hookEngine, BlockingQueue<AgentEvent> eventQueue) {
        this(registry, checker, hookEngine, eventQueue, null);
    }

    public StreamingExecutor(ToolRegistry registry, PermissionChecker checker,
                             HookEngine hookEngine, BlockingQueue<AgentEvent> eventQueue,
                             RecoveryState recoveryState) {
        this(registry, checker, hookEngine, eventQueue, recoveryState,
                Path.of(System.getProperty("user.dir")), "default");
    }

    public StreamingExecutor(ToolRegistry registry, PermissionChecker checker,
                             HookEngine hookEngine, BlockingQueue<AgentEvent> eventQueue,
                             RecoveryState recoveryState, Path workspace, String sessionId) {
        this.registry = registry;
        this.checker = checker;
        this.hookEngine = hookEngine;
        this.eventQueue = eventQueue;
        this.recoveryState = recoveryState;
        this.runId = java.util.UUID.randomUUID().toString();
        this.ledger = new JsonlToolExecutionLedger(workspace, sessionId, runId);
    }

    public List<ToolExecResult> executeAll(List<ToolCallInfo> calls) {
        // 按相邻性分批：连续的只读工具合成一个并行批次，写/命令工具各自独占一批
        var batches = partitionToolCalls(calls);
        var results = new ArrayList<ToolExecResult>();

        for (var batch : batches) {
            if (batch.concurrent && batch.calls.size() > 1) {
                try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                    var futures = batch.calls.stream()
                            .map(call -> executor.submit(() -> executeSingle(call)))
                            .toList();
                    for (var future : futures) {
                        try { results.add(future.get()); }
                        catch (Exception ignored) {}
                    }
                }
            } else {
                for (var call : batch.calls) results.add(executeSingle(call));
            }
        }

        return results;
    }

    private record ToolBatch(boolean concurrent, List<ToolCallInfo> calls) {}

    private List<ToolBatch> partitionToolCalls(List<ToolCallInfo> calls) {
        var batches = new ArrayList<ToolBatch>();
        for (var call : calls) {
            var tool = registry.get(call.toolName());
            boolean safe = tool != null && tool.category() == ToolCategory.READ;

            if (safe && !batches.isEmpty() && batches.getLast().concurrent()) {
                batches.getLast().calls().add(call);
            } else {
                batches.add(new ToolBatch(safe, new ArrayList<>(List.of(call))));
            }
        }
        return batches;
    }

    private ToolExecResult executeSingle(ToolCallInfo call) {
        Tool tool = registry.get(call.toolName());
        if (tool == null) {
            putSafe(new AgentEvent.ToolResultEvent(call.toolId(), call.toolName(), "Unknown tool", true, 0));
            return new ToolExecResult(call.toolId(), "Error: unknown tool '" + call.toolName() + "'", true);
        }

        String argsHash = hashArgs(call.args());
        if (tool.effect().sideEffect()) {
            var previous = ledger.find(call.toolId());
            if (previous.isPresent()) {
                var record = previous.orElseThrow();
                if (!record.argsHash().equals(argsHash)) return new ToolExecResult(call.toolId(), "Tool call id was reused with different arguments", true);
                if (record.status() == ToolExecutionRecord.Status.COMPLETED || record.status() == ToolExecutionRecord.Status.FAILED)
                    return new ToolExecResult(call.toolId(), record.result(), record.status() == ToolExecutionRecord.Status.FAILED);
                if (record.status() == ToolExecutionRecord.Status.UNKNOWN)
                    return new ToolExecResult(call.toolId(), "Side effect outcome is UNKNOWN; explicit reconciliation is required", true);
            }
        }

        MutationPreview preview;
        try { preview = MutationPreview.prepare(call.toolName(), call.args()); }
        catch (Exception e) { return new ToolExecResult(call.toolId(), e.getMessage(), true); }

        // 权限检查优先于 hook（与 Go 版保持一致）：先拦截无权操作，再让 hook 介入
        if (checker != null) {
            var check = checker.check(tool, call.args());
            switch (check.decision()) {
                case DENY -> {
                    String msg = "Permission denied: " + check.reason();
                    putSafe(new AgentEvent.ToolResultEvent(call.toolId(), call.toolName(), msg, true, 0));
                    return new ToolExecResult(call.toolId(), msg, true);
                }
                case ASK -> {
                    if (tool.effect().sideEffect()) record(call, tool, ToolExecutionRecord.Status.AWAITING_APPROVAL, argsHash, preview, "");
                    var future = new CompletableFuture<PermissionResponse>();
                    String desc = checker.describeToolAction(call.toolName(), call.args());
                    if (preview != null) desc += "\n\n" + preview.diff() + "\nbefore=" + preview.beforeHash().substring(0, 12) + " diff=" + preview.diffHash().substring(0, 12);
                    putSafe(new AgentEvent.PermissionRequestEvent(call.toolName(), desc, future));
                    PermissionResponse response;
                    try {
                        response = future.get(5, TimeUnit.MINUTES);
                    } catch (Exception e) {
                        response = PermissionResponse.DENY;
                    }
                    if (response == PermissionResponse.DENY) {
                        if (tool.effect().sideEffect()) record(call, tool, ToolExecutionRecord.Status.FAILED, argsHash, preview, "User denied permission");
                        putSafe(new AgentEvent.ToolResultEvent(
                                call.toolId(), call.toolName(), "Permission denied by user", true, 0));
                        return new ToolExecResult(call.toolId(), "User denied permission", true);
                    }
                    if (response == PermissionResponse.ALLOW_ALWAYS) {
                        String content = extractContent(call.toolName(), call.args());
                        if (content != null) {
                            checker.addAllowAlwaysRule(call.toolName(), content);
                        }
                    }
                }
                case ALLOW -> {}
            }
        }

        if (preview != null) {
            try { preview.verifyCurrent(); }
            catch (Exception stale) { record(call, tool, ToolExecutionRecord.Status.FAILED, argsHash, preview, stale.getMessage()); return new ToolExecResult(call.toolId(), stale.getMessage(), true); }
        }

        // Pre-tool hook 在权限通过后执行，可拦截特定工具调用
        if (hookEngine != null) {
            var hookResult = hookEngine.runPreToolHooks(call.toolName(), call.args());
            if (hookResult.rejected()) {
                String msg = "Rejected by hook: " + hookResult.message();
                putSafe(new AgentEvent.ToolResultEvent(call.toolId(), call.toolName(), msg, true, 0));
                return new ToolExecResult(call.toolId(), msg, true);
            }
        }

        long start = System.nanoTime();
        if (tool.effect().sideEffect()) record(call, tool, ToolExecutionRecord.Status.PENDING, argsHash, preview, "");
        ToolResult result;
        try {
            result = tool.execute(call.args());
        } catch (Exception e) {
            result = ToolResult.error("Tool execution error: " + e.getMessage());
        }
        double elapsed = (System.nanoTime() - start) / 1_000_000_000.0;

        if (tool.effect().sideEffect()) record(call, tool, result.isError() ? ToolExecutionRecord.Status.FAILED : ToolExecutionRecord.Status.COMPLETED, argsHash, preview, result.output());

        if ("ReadFile".equals(call.toolName())) {
            recordReadPathForRecovery(call, result);
        }
        recordValidationForRecovery(call, result);

        String output = result.output();
        if (output.length() > ToolRegistry.MAX_OUTPUT_CHARS) {
            output = output.substring(0, ToolRegistry.MAX_OUTPUT_CHARS) + "\n... (truncated)";
        }

        putSafe(new AgentEvent.ToolResultEvent(call.toolId(), call.toolName(), output, result.isError(), elapsed));

        // Post-tool hooks
        if (hookEngine != null) {
            var ctx = new HookEngine.HookContext(
                    HookEngine.EventName.POST_TOOL_USE, call.toolName(), call.args(), null, null, null);
            hookEngine.runHooks(ctx);
        }

        return new ToolExecResult(call.toolId(), output, result.isError());
    }

    private void putSafe(AgentEvent event) {
        try {
            eventQueue.put(event);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Record the path of a successful ReadFile call. The compact recovery block
     * later lists these paths so the model can re-read exact current content.
     */
    private void recordReadPathForRecovery(ToolCallInfo call, ToolResult result) {
        if (recoveryState == null || result.isError()) return;
        Object pathObj = call.args() == null ? null : call.args().get("file_path");
        if (!(pathObj instanceof String) || ((String) pathObj).isEmpty()) return;
        recoveryState.recordFileRead((String) pathObj);
    }

    /** Records actual test/build/lint output so a later compact does not lose verification status. */
    private void recordValidationForRecovery(ToolCallInfo call, ToolResult result) {
        if (recoveryState == null || !("Bash".equals(call.toolName()) || "PowerShell".equals(call.toolName()))) return;
        Object commandObj = call.args() == null ? null : call.args().get("command");
        if (!(commandObj instanceof String command) || !isValidationCommand(command)) return;
        String output = result.output() == null ? "" : result.output();
        if (output.length() > 2_000) output = output.substring(0, 2_000) + "\n… (output truncated)";
        recoveryState.recordValidation(command, !result.isError(), output);
    }

    private static boolean isValidationCommand(String command) {
        String lower = command.toLowerCase(java.util.Locale.ROOT);
        return lower.matches("(?s).*\\b(test|verify|verification|check|lint|compile|build|package|pytest|jest|mvn|gradle)\\b.*");
    }

    private static String extractContent(String toolName, Map<String, Object> args) {
        String field = switch (toolName) {
            case "Bash" -> "command";
            case "ReadFile", "WriteFile", "EditFile" -> "file_path";
            case "Glob", "Grep" -> "pattern";
            default -> null;
        };
        if (field == null) return null;
        var v = args.get(field);
        return v instanceof String s ? s : null;
    }

    private void record(ToolCallInfo call, Tool tool, ToolExecutionRecord.Status status,
                        String argsHash, MutationPreview preview, String result) {
        String after = "";
        if (preview != null && java.nio.file.Files.exists(preview.path())) {
            try { after = sha256(java.nio.file.Files.readString(preview.path())); } catch (Exception ignored) {}
        }
        ledger.append(new ToolExecutionRecord(call.toolId(), call.toolName(), tool.effect(), status,
                argsHash, preview == null ? "" : preview.beforeHash(), after,
                preview == null ? "" : preview.diffHash(), result == null ? "" : result, runId, java.time.Instant.now()));
    }

    private static String hashArgs(Map<String,Object> args) {
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper().enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
            return sha256(mapper.writeValueAsString(args == null ? Map.of() : args));
        } catch (Exception e) { throw new IllegalArgumentException("Cannot hash tool arguments", e); }
    }
    private static String sha256(String value) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
