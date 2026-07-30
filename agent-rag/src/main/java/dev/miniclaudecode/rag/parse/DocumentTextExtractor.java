package dev.miniclaudecode.rag.parse;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/** Converts supported non-code documents to structured, searchable UTF-8 text. */
public interface DocumentTextExtractor {
  boolean supports(Path path);

  Optional<String> extract(Path path, byte[] bytes) throws IOException;
}
