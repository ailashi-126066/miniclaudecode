package dev.miniclaudecode.persistence.checkpoint;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver.Tag;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.bsc.langgraph4j.checkpoint.FileSystemSaver;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.AgentStateFactory;

public final class FileCheckpointSaver<State extends AgentState> implements BaseCheckpointSaver {
  private final FileSystemSaver delegate;

  public FileCheckpointSaver(Path root, AgentStateFactory<State> stateFactory) {
    Objects.requireNonNull(root, "root must not be null");
    Objects.requireNonNull(stateFactory, "stateFactory must not be null");
    this.delegate = new FileSystemSaver(root, new ObjectStreamStateSerializer<>(stateFactory));
  }

  public Collection<Checkpoint> list(RunnableConfig config) {
    return this.delegate.list(config);
  }

  public Optional<Checkpoint> get(RunnableConfig config) {
    return this.delegate.get(config);
  }

  public RunnableConfig put(RunnableConfig config, Checkpoint checkpoint) throws Exception {
    return this.delegate.put(config, checkpoint);
  }

  public Tag release(RunnableConfig config) throws Exception {
    return this.delegate.release(config);
  }
}
