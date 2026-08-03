package dev.miniclaudecode.rag.embedding;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.output.Response;
import java.util.List;

/** Marks embedding backends that safely support concurrent batch inference. */
public interface BatchEmbeddingModel {
  Response<List<Embedding>> embedAll(List<TextSegment> segments);
}
