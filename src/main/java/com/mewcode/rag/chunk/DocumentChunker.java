package com.mewcode.rag.chunk;

import java.util.List;

@FunctionalInterface
public interface DocumentChunker {
  List<CodeChunk> chunk(String path, String content);
}
