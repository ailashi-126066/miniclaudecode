package com.mewcode.llm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mewcode.config.ProviderConfig;
import com.mewcode.conversation.ConversationManager;
import com.mewcode.conversation.ConversationProtocol;
import com.mewcode.conversation.Message;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.CompleteToolCall;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/** LangChain4j transport adapter that preserves MewCode's bounded queue contract. */
public class LangChainClient implements LlmClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ProviderConfig config;
    private volatile String systemPrompt;
    private volatile int maxOutputTokens;

    public LangChainClient(ProviderConfig config, String systemPrompt) {
        this.config = config;
        this.systemPrompt = systemPrompt == null ? "" : systemPrompt;
        this.maxOutputTokens = config.resolvedMaxOutputTokens();
    }

    @Override
    public void setSystemPrompt(String prompt) {
        this.systemPrompt = prompt == null ? "" : prompt;
    }

    @Override
    public void setMaxOutputTokens(int tokens) {
        if (tokens > 0) this.maxOutputTokens = tokens;
    }

    @Override
    public BlockingQueue<StreamEvent> stream(ConversationManager conv, List<Map<String, Object>> tools) {
        var queue = new LinkedBlockingQueue<StreamEvent>(64);
        List<Message> snapshot = conv == null ? List.of() : conv.getMessages();
        List<Map<String, Object>> toolSnapshot = tools == null ? List.of() : List.copyOf(tools);
        String promptSnapshot = systemPrompt;
        int tokenSnapshot = maxOutputTokens;
        Thread.ofVirtual().name("minicode-model-stream").start(() -> {
            try {
                StreamingChatModel model = buildModel(tokenSnapshot);
                ChatRequest request = ChatRequest.builder()
                        .messages(toMessages(snapshot, promptSnapshot))
                        .modelName(config.getModel())
                        .maxOutputTokens(tokenSnapshot)
                        .toolSpecifications(toToolSpecifications(toolSnapshot))
                        .build();
                model.chat(request, handler(queue));
            } catch (Throwable failure) {
                put(queue, new StreamEvent.Error(LlmException.classify(failure)));
            }
        });
        return queue;
    }

    private StreamingChatModel buildModel(int maxTokens) {
        String protocol = config.getProtocol();
        String baseUrl = trimSlash(config.getBaseUrl());
        String key = config.resolvedApiKey();
        Duration timeout = Duration.ofMinutes(5);
        return switch (protocol) {
            case "anthropic" -> {
                var builder = AnthropicStreamingChatModel.builder()
                        .apiKey(key).modelName(config.getModel()).maxTokens(maxTokens)
                        .timeout(timeout).returnThinking(true).sendThinking(true);
                if (!baseUrl.isBlank()) builder.baseUrl(baseUrl.endsWith("/v1") ? baseUrl : baseUrl + "/v1");
                if (config.isThinking()) builder.thinkingType("enabled")
                        .thinkingBudgetTokens(Math.max(1024, Math.min(maxTokens / 2, 8192)))
                        .thinkingDisplay("summarized");
                yield builder.build();
            }
            case "openai", "openai-compat" -> {
                var builder = OpenAiStreamingChatModel.builder()
                        .apiKey(key.isBlank() ? "local" : key).modelName(config.getModel())
                        .maxCompletionTokens(maxTokens).timeout(timeout)
                        .returnThinking(true).sendThinking(true);
                if (!baseUrl.isBlank()) builder.baseUrl(baseUrl);
                if (config.isThinking()) builder.reasoningEffort("medium");
                yield builder.build();
            }
            case "ollama" -> OllamaStreamingChatModel.builder()
                    .baseUrl(baseUrl.isBlank() ? "http://localhost:11434" : baseUrl)
                    .modelName(config.getModel()).numPredict(maxTokens)
                    .think(config.isThinking()).returnThinking(true).timeout(timeout).build();
            default -> throw new IllegalArgumentException("Unknown protocol: " + protocol);
        };
    }

    private StreamingChatResponseHandler handler(BlockingQueue<StreamEvent> queue) {
        Map<Integer, PendingTool> pending = new HashMap<>();
        return new StreamingChatResponseHandler() {
            @Override public void onPartialResponse(String text) {
                if (text != null && !text.isEmpty()) put(queue, new StreamEvent.TextDelta(text));
            }
            @Override public void onPartialThinking(PartialThinking thinking) {
                if (thinking != null && !thinking.text().isEmpty())
                    put(queue, new StreamEvent.ThinkingDelta(thinking.text()));
            }
            @Override public synchronized void onPartialToolCall(PartialToolCall part) {
                if (part == null) return;
                PendingTool p = pending.computeIfAbsent(part.index(), ignored -> new PendingTool());
                p.update(part.id(), part.name());
                if (!p.started && p.id != null && p.name != null) {
                    p.started = true;
                    put(queue, new StreamEvent.ToolCallStart(p.id, p.name));
                }
                String fragment = Optional.ofNullable(part.partialArguments()).orElse("");
                p.arguments.append(fragment);
                if (p.started && !fragment.isEmpty()) put(queue, new StreamEvent.ToolCallDelta(fragment));
            }
            @Override public synchronized void onCompleteToolCall(CompleteToolCall complete) {
                ToolExecutionRequest tool = complete.toolExecutionRequest();
                PendingTool p = pending.computeIfAbsent(complete.index(), ignored -> new PendingTool());
                p.update(tool.id(), tool.name());
                if (!p.started) put(queue, new StreamEvent.ToolCallStart(p.id, p.name));
                String argsJson = tool.arguments() == null || tool.arguments().isBlank() ? "{}" : tool.arguments();
                try {
                    Map<String, Object> args = JSON.readValue(argsJson, new TypeReference<>() {});
                    put(queue, new StreamEvent.ToolCallComplete(p.id, p.name, args));
                } catch (Exception e) {
                    put(queue, new StreamEvent.Error(new LlmException("Invalid tool arguments for " + p.name + ": " + e.getMessage(), e)));
                }
                pending.remove(complete.index());
            }
            @Override public synchronized void onCompleteResponse(ChatResponse response) {
                if (!pending.isEmpty()) {
                    put(queue, new StreamEvent.Error(new LlmException("Provider left unfinished tool calls")));
                    return;
                }
                int input = response == null || response.tokenUsage() == null || response.tokenUsage().inputTokenCount() == null ? 0 : response.tokenUsage().inputTokenCount();
                int output = response == null || response.tokenUsage() == null || response.tokenUsage().outputTokenCount() == null ? 0 : response.tokenUsage().outputTokenCount();
                String reason = response == null || response.finishReason() == null ? "end_turn" : response.finishReason().name().toLowerCase();
                put(queue, new StreamEvent.StreamEnd(reason, input, output));
            }
            @Override public void onError(Throwable error) { put(queue, new StreamEvent.Error(LlmException.classify(error))); }
        };
    }

    private static List<ChatMessage> toMessages(List<Message> source, String systemPrompt) {
        List<ChatMessage> out = new ArrayList<>();
        if (!systemPrompt.isBlank()) out.add(SystemMessage.from(systemPrompt));
        Map<String, String> toolNames = new HashMap<>();
        for (Message m : ConversationProtocol.messagesForProvider(source)) {
            if (m.getToolUses() != null) m.getToolUses().forEach(t -> toolNames.put(t.toolUseId(), t.toolName()));
            if (m.getToolResults() != null && !m.getToolResults().isEmpty()) {
                for (var r : m.getToolResults()) out.add(ToolExecutionResultMessage.builder()
                        .id(r.toolUseId()).toolName(toolNames.getOrDefault(r.toolUseId(), "tool"))
                        .text(r.content()).isError(r.isError()).build());
            } else if ("assistant".equals(m.getRole())) {
                var builder = AiMessage.builder().text(m.getContent() == null ? "" : m.getContent());
                // NOTE: thinking 块不能发送回 API（Anthropic API 不接受请求中的 thinking）
                // thinking 块仅用于显示给用户，维护对话历史时必须过滤掉
                if (m.getToolUses() != null) builder.toolExecutionRequests(m.getToolUses().stream().map(t -> ToolExecutionRequest.builder()
                        .id(t.toolUseId()).name(t.toolName()).arguments(writeJson(t.arguments())).build()).toList());
                out.add(builder.build());
            } else {
                out.add(UserMessage.from(m.getContent() == null ? "" : m.getContent()));
            }
        }
        return out;
    }

    private static List<ToolSpecification> toToolSpecifications(List<Map<String, Object>> schemas) {
        List<ToolSpecification> out = new ArrayList<>();
        for (Map<String, Object> raw : schemas) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("name", raw.get("name"));
            normalized.put("description", raw.getOrDefault("description", ""));
            normalized.put("parameters", raw.containsKey("input_schema") ? raw.get("input_schema") : raw.get("parameters"));
            try { out.add(ToolSpecification.fromJson(writeJson(normalized))); }
            catch (RuntimeException e) { throw new IllegalArgumentException("Invalid tool schema: " + raw.get("name"), e); }
        }
        return out;
    }

    private static String writeJson(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalArgumentException("JSON encoding failed", e); }
    }
    private static String trimSlash(String value) {
        if (value == null) return "";
        String result = value.trim();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }
    private static void put(BlockingQueue<StreamEvent> queue, StreamEvent event) {
        try { queue.put(event); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
    private static final class PendingTool {
        String id; String name; boolean started; final StringBuilder arguments = new StringBuilder();
        void update(String id, String name) { if (id != null && !id.isBlank()) this.id = id; if (name != null && !name.isBlank()) this.name = name; }
    }
}
