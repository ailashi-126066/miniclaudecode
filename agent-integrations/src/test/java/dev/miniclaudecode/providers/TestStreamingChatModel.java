package dev.miniclaudecode.providers;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public final class TestStreamingChatModel implements StreamingChatModel {
  private final Consumer<StreamingChatResponseHandler> script;
  private final AtomicInteger callCount = new AtomicInteger();
  private volatile ChatRequest request;

  public TestStreamingChatModel(Consumer<StreamingChatResponseHandler> script) {
    this.script = Objects.requireNonNull(script, "script must not be null");
  }

  @Override
  public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
    this.request = request;
    callCount.incrementAndGet();
    script.accept(handler);
  }

  public ChatRequest request() {
    return request;
  }

  public int callCount() {
    return callCount.get();
  }
}
