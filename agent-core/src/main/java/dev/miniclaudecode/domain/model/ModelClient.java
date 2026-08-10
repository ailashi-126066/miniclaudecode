package dev.miniclaudecode.domain.model;

import java.util.concurrent.Flow.Publisher;

@FunctionalInterface
public interface ModelClient {
  Publisher<ModelStreamEvent> stream(ModelRequest request);
}
