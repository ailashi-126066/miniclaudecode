package dev.miniclaudecode.providers;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.anthropic.AnthropicTokenUsage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.CompleteToolCall;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import dev.langchain4j.model.output.TokenUsage;
import dev.miniclaudecode.domain.message.AgentMessage;
import dev.miniclaudecode.domain.model.ModelClient;
import dev.miniclaudecode.domain.model.ModelRequest;
import dev.miniclaudecode.domain.model.ModelStreamEvent;
import dev.miniclaudecode.domain.tool.ToolCall;
import dev.miniclaudecode.domain.tool.ToolDescriptor;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public abstract class LangChainStreamingModelClient implements ModelClient {

  private static final ScheduledExecutorService STREAM_WATCHDOG =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "model-stream-watchdog");
            thread.setDaemon(true);
            return thread;
          });

  private final StreamingChatModel model;
  private final ThinkingSupport thinkingSupport;
  private final Optional<String> secret;
  private final Duration streamIdleTimeout;

  protected LangChainStreamingModelClient(
      StreamingChatModel model, ThinkingSupport thinkingSupport, Optional<String> secret) {
    this(model, thinkingSupport, secret, null);
  }

  protected LangChainStreamingModelClient(
      StreamingChatModel model,
      ThinkingSupport thinkingSupport,
      Optional<String> secret,
      Duration streamIdleTimeout) {
    this.model = Objects.requireNonNull(model, "model must not be null");
    this.thinkingSupport =
        Objects.requireNonNull(thinkingSupport, "thinkingSupport must not be null");
    this.secret = Objects.requireNonNull(secret, "secret must not be null");
    this.streamIdleTimeout = streamIdleTimeout;
  }

  public final ThinkingSupport thinkingSupport() {
    return thinkingSupport;
  }

  @Override
  public final Flow.Publisher<ModelStreamEvent> stream(ModelRequest request) {
    Objects.requireNonNull(request, "request must not be null");
    if (request.thinkingEnabled() && thinkingSupport == ThinkingSupport.UNSUPPORTED) {
      return new ImmediatePublisher(
          new ModelStreamEvent.Failed(
              "thinking_unsupported",
              "the selected provider does not support thinking summaries",
              false));
    }
    ToolNameMapping names = ToolNameMapping.from(request.tools());
    ChatRequest chatRequest = toChatRequest(request, names);
    return new CallbackPublisher(
        model, chatRequest, names, request.thinkingEnabled(), secret, streamIdleTimeout);
  }

  private static ChatRequest toChatRequest(ModelRequest request, ToolNameMapping names) {
    List<ChatMessage> messages =
        request.messages().stream().map(message -> toChatMessage(message, names)).toList();
    List<ToolSpecification> specifications =
        request.tools().stream().map(tool -> toToolSpecification(tool, names)).toList();
    return ChatRequest.builder()
        .messages(messages)
        .modelName(request.modelName())
        .maxOutputTokens(request.maxOutputTokens())
        .toolSpecifications(specifications)
        .build();
  }

  private static ChatMessage toChatMessage(AgentMessage message, ToolNameMapping names) {
    return switch (message) {
      case AgentMessage.SystemMessage system -> SystemMessage.from(system.text());
      case AgentMessage.UserMessage user -> UserMessage.from(user.text());
      case AgentMessage.AssistantMessage assistant ->
          AiMessage.builder()
              .text(assistant.text())
              .thinking(assistant.thinking().orElse(null))
              .toolExecutionRequests(
                  assistant.toolCalls().stream()
                      .map(
                          call ->
                              ToolExecutionRequest.builder()
                                  .id(call.toolCallId())
                                  .name(names.providerName(call.qualifiedName()))
                                  .arguments(call.argumentsJson())
                                  .build())
                      .toList())
              .attributes(assistant.providerMetadata())
              .build();
      case AgentMessage.ToolMessage tool ->
          ToolExecutionResultMessage.builder()
              .id(tool.toolCallId())
              .toolName(names.providerName(tool.qualifiedToolName()))
              .text(tool.text())
              .isError(tool.error())
              .build();
    };
  }

  private static ToolSpecification toToolSpecification(
      ToolDescriptor descriptor, ToolNameMapping names) {
    String json =
        "{\"name\":\""
            + escapeJson(names.providerName(descriptor.qualifiedName()))
            + "\",\"description\":\""
            + escapeJson(descriptor.description())
            + "\",\"parameters\":"
            + descriptor.inputSchemaJson()
            + "}";
    try {
      return ToolSpecification.fromJson(json);
    } catch (RuntimeException exception) {
      throw new IllegalArgumentException(
          "invalid JSON schema for tool " + descriptor.qualifiedName(), exception);
    }
  }

  private static String escapeJson(String text) {
    StringBuilder escaped = new StringBuilder(text.length() + 16);
    for (int index = 0; index < text.length(); index++) {
      char character = text.charAt(index);
      switch (character) {
        case '"' -> escaped.append("\\\"");
        case '\\' -> escaped.append("\\\\");
        case '\b' -> escaped.append("\\b");
        case '\f' -> escaped.append("\\f");
        case '\n' -> escaped.append("\\n");
        case '\r' -> escaped.append("\\r");
        case '\t' -> escaped.append("\\t");
        default -> {
          if (character < 0x20) {
            escaped.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
          } else {
            escaped.append(character);
          }
        }
      }
    }
    return escaped.toString();
  }

  private record ToolNameMapping(
      Map<String, String> qualifiedToProvider, Map<String, String> providerToQualified) {

    private static ToolNameMapping from(List<ToolDescriptor> tools) {
      Map<String, String> forward = new LinkedHashMap<>();
      Map<String, String> reverse = new LinkedHashMap<>();
      for (int index = 0; index < tools.size(); index++) {
        ToolDescriptor tool = tools.get(index);
        String safe =
            "tool_"
                + index
                + "_"
                + tool.namespace().replaceAll("[^A-Za-z0-9_-]", "_")
                + "_"
                + tool.name().replaceAll("[^A-Za-z0-9_-]", "_");
        forward.put(tool.qualifiedName(), safe);
        reverse.put(safe, tool.qualifiedName());
      }
      return new ToolNameMapping(Map.copyOf(forward), Map.copyOf(reverse));
    }

    private String providerName(String qualifiedName) {
      return qualifiedToProvider.getOrDefault(qualifiedName, sanitizeUnknown(qualifiedName));
    }

    private String qualifiedName(String providerName) {
      return providerToQualified.getOrDefault(providerName, providerName);
    }

    private static String sanitizeUnknown(String value) {
      return value.replaceAll("[^A-Za-z0-9_-]", "_");
    }
  }

  private static final class CallbackPublisher implements Flow.Publisher<ModelStreamEvent> {

    private final StreamingChatModel model;
    private final ChatRequest request;
    private final ToolNameMapping names;
    private final boolean thinkingEnabled;
    private final Optional<String> secret;
    private final Duration idleTimeout;

    private CallbackPublisher(
        StreamingChatModel model,
        ChatRequest request,
        ToolNameMapping names,
        boolean thinkingEnabled,
        Optional<String> secret,
        Duration idleTimeout) {
      this.model = model;
      this.request = request;
      this.names = names;
      this.thinkingEnabled = thinkingEnabled;
      this.secret = secret;
      this.idleTimeout = idleTimeout;
    }

    @Override
    public void subscribe(Flow.Subscriber<? super ModelStreamEvent> subscriber) {
      Objects.requireNonNull(subscriber, "subscriber must not be null");
      BridgeSubscription subscription =
          new BridgeSubscription(subscriber, names, thinkingEnabled, secret);
      subscriber.onSubscribe(subscription);
      if (!subscription.isCancelled()) {
        try {
          model.chat(request, subscription.handler());
          subscription.armIdleWatchdog(idleTimeout);
        } catch (RuntimeException exception) {
          subscription.fail(exception);
        }
      }
    }
  }

  private static final class BridgeSubscription implements Flow.Subscription {

    private final Flow.Subscriber<? super ModelStreamEvent> subscriber;
    private final ToolNameMapping names;
    private final boolean thinkingEnabled;
    private final Optional<String> secret;
    private final Queue<ModelStreamEvent> queue = new ArrayDeque<>();
    private final Map<Integer, PendingTool> pendingTools = new HashMap<>();
    private long demand;
    private boolean cancelled;
    private boolean upstreamComplete;
    private boolean terminalDelivered;
    private boolean draining;
    private Duration idleTimeout;
    private java.util.concurrent.ScheduledFuture<?> idleCheck;
    private long lastActivityNanos;

    private BridgeSubscription(
        Flow.Subscriber<? super ModelStreamEvent> subscriber,
        ToolNameMapping names,
        boolean thinkingEnabled,
        Optional<String> secret) {
      this.subscriber = subscriber;
      this.names = names;
      this.thinkingEnabled = thinkingEnabled;
      this.secret = secret;
    }

    private StreamingChatResponseHandler handler() {
      return new StreamingChatResponseHandler() {
        @Override
        public void onPartialResponse(String partialResponse) {
          touch();
          if (partialResponse != null && !partialResponse.isEmpty()) {
            emit(new ModelStreamEvent.TextDelta(partialResponse));
          }
        }

        @Override
        public void onPartialThinking(PartialThinking partialThinking) {
          touch();
          if (thinkingEnabled && partialThinking != null && !partialThinking.text().isEmpty()) {
            emit(new ModelStreamEvent.ThinkingDelta(partialThinking.text()));
          }
        }

        @Override
        public void onPartialToolCall(PartialToolCall partialToolCall) {
          touch();
          acceptPartialToolCall(partialToolCall);
        }

        @Override
        public void onCompleteToolCall(CompleteToolCall completeToolCall) {
          touch();
          acceptCompleteToolCall(completeToolCall);
        }

        @Override
        public void onCompleteResponse(ChatResponse completeResponse) {
          complete(completeResponse);
        }

        @Override
        public void onError(Throwable error) {
          fail(error);
        }
      };
    }

    private synchronized void armIdleWatchdog(Duration timeout) {
      if (timeout == null || timeout.isZero() || timeout.isNegative()) {
        return;
      }
      if (cancelled || terminalDelivered || upstreamComplete) {
        return;
      }
      idleTimeout = timeout;
      touch();
      scheduleIdleCheck(timeout);
    }

    private void scheduleIdleCheck(Duration delay) {
      this.idleCheck =
          STREAM_WATCHDOG.schedule(this::checkIdle, delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Cancels the pending idle check.
     *
     * <p>The scheduled task holds a strong reference to this subscription, and through it to the
     * subscriber and the full message history of the turn. Leaving it queued after the stream ends
     * pinned one transcript snapshot per model call for the length of the provider timeout.
     */
    private void cancelIdleWatchdog() {
      java.util.concurrent.ScheduledFuture<?> pending = this.idleCheck;
      if (pending != null) {
        pending.cancel(false);
        this.idleCheck = null;
      }
      this.idleTimeout = null;
    }

    private synchronized void checkIdle() {
      if (cancelled || terminalDelivered || upstreamComplete || idleTimeout == null) {
        return;
      }
      long idleNanos = System.nanoTime() - lastActivityNanos;
      if (idleNanos >= idleTimeout.toNanos()) {
        fail(
            new IllegalStateException(
                "the provider stream timed out after "
                    + idleTimeout.toSeconds()
                    + " seconds without events; check that base-url points at a compatible API"
                    + " endpoint"));
        return;
      }
      scheduleIdleCheck(idleTimeout.minusNanos(idleNanos));
    }

    private synchronized void touch() {
      lastActivityNanos = System.nanoTime();
    }

    @Override
    public synchronized void request(long requested) {
      if (cancelled || terminalDelivered) {
        return;
      }
      if (requested <= 0) {
        cancelled = true;
        terminalDelivered = true;
        subscriber.onError(new IllegalArgumentException("demand must be greater than zero"));
        return;
      }
      demand = addWithSaturation(demand, requested);
      drain();
    }

    @Override
    public synchronized void cancel() {
      cancelled = true;
      queue.clear();
      pendingTools.clear();
      cancelIdleWatchdog();
    }

    private synchronized boolean isCancelled() {
      return cancelled;
    }

    private synchronized void emit(ModelStreamEvent event) {
      if (!cancelled && !terminalDelivered) {
        queue.add(event);
        drain();
      }
    }

    private synchronized void acceptPartialToolCall(PartialToolCall partial) {
      if (cancelled || terminalDelivered || partial == null) {
        return;
      }
      PendingTool pending =
          pendingTools.computeIfAbsent(partial.index(), ignored -> new PendingTool());
      pending.update(partial.id(), partial.name());
      String fragment = Optional.ofNullable(partial.partialArguments()).orElse("");
      pending.arguments.append(fragment);
      startIfReady(pending);
      if (pending.started && !fragment.isEmpty()) {
        queue.add(new ModelStreamEvent.ToolCallDelta(pending.id, fragment));
      }
      drain();
    }

    private synchronized void acceptCompleteToolCall(CompleteToolCall complete) {
      if (cancelled || terminalDelivered || complete == null) {
        return;
      }
      ToolExecutionRequest tool = complete.toolExecutionRequest();
      PendingTool pending =
          pendingTools.computeIfAbsent(complete.index(), ignored -> new PendingTool());
      pending.update(tool.id(), tool.name());
      startIfReady(pending);
      if (!pending.started) {
        fail(new IllegalStateException("provider completed a tool call without id or name"));
        return;
      }
      String completeArguments =
          Optional.ofNullable(tool.arguments()).filter(v -> !v.isBlank()).orElse("{}");
      String accumulated = pending.arguments.toString();
      if (!completeArguments.equals(accumulated)) {
        String missing =
            completeArguments.startsWith(accumulated)
                ? completeArguments.substring(accumulated.length())
                : completeArguments;
        if (!missing.isEmpty()) {
          queue.add(new ModelStreamEvent.ToolCallDelta(pending.id, missing));
        }
      }
      queue.add(
          new ModelStreamEvent.ToolCallCompleted(
              new ToolCall(pending.id, names.qualifiedName(pending.name), completeArguments)));
      pendingTools.remove(complete.index());
      drain();
    }

    private void startIfReady(PendingTool pending) {
      if (!pending.started && pending.id != null && pending.name != null) {
        pending.started = true;
        queue.add(
            new ModelStreamEvent.ToolCallStarted(pending.id, names.qualifiedName(pending.name)));
      }
    }

    private synchronized void complete(ChatResponse response) {
      if (cancelled || terminalDelivered) {
        return;
      }
      if (!pendingTools.isEmpty()) {
        fail(new IllegalStateException("provider left unfinished tool calls"));
        return;
      }
      TokenUsage usage = response == null ? null : response.tokenUsage();
      if (usage != null) {
        queue.add(toUsageEvent(usage));
      }
      Map<String, Object> metadata = new LinkedHashMap<>();
      if (response != null && response.id() != null) {
        metadata.put("responseId", response.id());
      }
      if (response != null && response.modelName() != null) {
        metadata.put("model", response.modelName());
      }
      String finishReason =
          response == null || response.finishReason() == null
              ? "unknown"
              : response.finishReason().name().toLowerCase(Locale.ROOT);
      queue.add(new ModelStreamEvent.Completed(finishReason, metadata));
      upstreamComplete = true;
      drain();
    }

    private synchronized void fail(Throwable error) {
      if (cancelled || terminalDelivered) {
        return;
      }
      Throwable failure =
          error == null ? new IllegalStateException("unknown provider error") : error;
      ErrorDetails details = ErrorDetails.from(failure, secret);
      queue.add(
          new ModelStreamEvent.Failed(details.type(), details.message(), details.retryable()));
      upstreamComplete = true;
      drain();
    }

    private void drain() {
      if (draining) {
        return;
      }
      draining = true;
      try {
        while (!cancelled && !terminalDelivered && demand > 0 && !queue.isEmpty()) {
          ModelStreamEvent event = queue.remove();
          demand--;
          subscriber.onNext(event);
        }
        if (!cancelled && !terminalDelivered && upstreamComplete && queue.isEmpty()) {
          terminalDelivered = true;
          cancelIdleWatchdog();
          subscriber.onComplete();
        }
      } finally {
        draining = false;
      }
    }

    private static long addWithSaturation(long left, long right) {
      long result = left + right;
      return result < 0 ? Long.MAX_VALUE : result;
    }

    private static long tokenCount(Integer value) {
      return value == null ? 0 : Math.max(0, value.longValue());
    }

    private static ModelStreamEvent.UsageReported toUsageEvent(TokenUsage usage) {
      long providerInput = tokenCount(usage.inputTokenCount());
      long output = tokenCount(usage.outputTokenCount());
      if (usage instanceof AnthropicTokenUsage anthropic) {
        long cacheWrite = tokenCount(anthropic.cacheCreationInputTokens());
        long cacheRead = tokenCount(anthropic.cacheReadInputTokens());
        return new ModelStreamEvent.UsageReported(
            providerInput + cacheRead + cacheWrite, output, cacheRead, cacheWrite);
      }
      if (usage instanceof OpenAiTokenUsage openAi && openAi.inputTokensDetails() != null) {
        long cacheRead = tokenCount(openAi.inputTokensDetails().cachedTokens());
        return new ModelStreamEvent.UsageReported(providerInput, output, cacheRead, 0);
      }
      return new ModelStreamEvent.UsageReported(providerInput, output);
    }
  }

  private static final class PendingTool {
    private String id;
    private String name;
    private final StringBuilder arguments = new StringBuilder();
    private boolean started;

    private void update(String nextId, String nextName) {
      if (nextId != null && !nextId.isBlank()) {
        id = nextId;
      }
      if (nextName != null && !nextName.isBlank()) {
        name = nextName;
      }
    }
  }

  private record ErrorDetails(String type, String message, boolean retryable) {

    /**
     * Classifies a provider failure into a retry decision.
     *
     * <p>Message text alone is not enough. Anthropic's {@code overloaded_error} carries no status
     * code, so a pure substring scan marked the single most retryable failure a provider emits as
     * permanent; conversely a schema complaint naming the {@code timeoutSeconds} tool property was
     * read as a timeout and retried three times. This walks the cause chain for typed transport
     * exceptions first, then falls back to word-boundary matching so a field name embedded in a
     * larger identifier can no longer trip a keyword.
     */
    private static ErrorDetails from(Throwable error, Optional<String> secret) {
      String raw = Optional.ofNullable(error.getMessage()).orElse(error.getClass().getSimpleName());
      String lower = raw.toLowerCase(Locale.ROOT);

      boolean typedTimeout = hasCause(error, java.net.http.HttpTimeoutException.class);
      boolean typedTransport =
          typedTimeout
              || hasCause(error, java.net.ConnectException.class)
              || hasCause(error, java.net.SocketTimeoutException.class)
              || hasCause(error, java.net.UnknownHostException.class)
              || hasCause(error, javax.net.ssl.SSLHandshakeException.class)
              || hasCause(error, java.io.EOFException.class);

      // Non-retryable first: retrying a bad key or a malformed request only burns quota and hides
      // the real cause behind three backoff delays.
      boolean forbidden =
          matchesWord(lower, "401")
              || matchesWord(lower, "403")
              || lower.contains("unauthorized")
              || lower.contains("authentication")
              || lower.contains("invalid api key")
              || lower.contains("invalid_api_key")
              || lower.contains("permission_error")
              || lower.contains("invalid_request");

      boolean rateLimited =
          !forbidden
              && (matchesWord(lower, "429")
                  || lower.contains("rate limit")
                  || lower.contains("rate_limit"));
      boolean timeout =
          !forbidden
              && (typedTimeout || matchesWord(lower, "timeout") || lower.contains("timed out"));
      boolean overloaded =
          !forbidden
              && (lower.contains("overloaded")
                  || lower.contains("capacity")
                  || lower.contains("service unavailable")
                  || lower.contains("try again later"));
      boolean server =
          !forbidden
              && (lower.matches("(?s).*\\b5\\d\\d\\b.*")
                  || lower.contains("internal server error")
                  || lower.contains("bad gateway"));
      boolean transportFailure =
          !forbidden
              && (typedTransport
                  || lower.contains("connection reset")
                  || lower.contains("unexpected end of stream")
                  || lower.contains("premature")
                  || lower.contains("goaway")
                  || lower.contains("stream closed"));

      String type;
      if (forbidden) {
        type = "invalid_request";
      } else if (rateLimited) {
        type = "rate_limited";
      } else if (timeout) {
        type = "timeout";
      } else if (overloaded || server) {
        type = "503";
      } else if (transportFailure) {
        type = "transport_error";
      } else {
        type = "provider_error";
      }

      String safe =
          secret
              .filter(value -> !value.isEmpty())
              .map(value -> raw.replace(value, "***"))
              .orElse(raw);
      safe = safe.replaceAll("(?i)bearer\\s+[A-Za-z0-9._-]+", "Bearer ***");
      safe = safe.replaceAll("(?i)(sk-|sk-ant-)[A-Za-z0-9._-]{8,}", "$1***");
      if (safe.length() > 500) {
        safe = safe.substring(0, 500);
      }
      boolean retryable =
          !forbidden && (rateLimited || timeout || overloaded || server || transportFailure);
      return new ErrorDetails(type, safe, retryable);
    }

    /** Word-boundary match, so {@code timeoutSeconds} does not look like a timeout. */
    private static boolean matchesWord(String text, String word) {
      return text.matches("(?s).*\\b" + java.util.regex.Pattern.quote(word) + "\\b.*");
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
      for (Throwable current = error; current != null; current = current.getCause()) {
        if (type.isInstance(current)) {
          return true;
        }
        if (current.getCause() == current) {
          return false;
        }
      }
      return false;
    }
  }

  private static final class ImmediatePublisher implements Flow.Publisher<ModelStreamEvent> {
    private final ModelStreamEvent event;

    private ImmediatePublisher(ModelStreamEvent event) {
      this.event = event;
    }

    @Override
    public void subscribe(Flow.Subscriber<? super ModelStreamEvent> subscriber) {
      Objects.requireNonNull(subscriber, "subscriber must not be null");
      subscriber.onSubscribe(
          new Flow.Subscription() {
            private boolean done;

            @Override
            public void request(long demand) {
              if (done) {
                return;
              }
              done = true;
              if (demand <= 0) {
                subscriber.onError(
                    new IllegalArgumentException("demand must be greater than zero"));
              } else {
                subscriber.onNext(event);
                subscriber.onComplete();
              }
            }

            @Override
            public void cancel() {
              done = true;
            }
          });
    }
  }
}
