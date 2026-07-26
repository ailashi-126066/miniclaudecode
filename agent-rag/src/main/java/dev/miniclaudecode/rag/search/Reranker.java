package dev.miniclaudecode.rag.search;

import java.util.List;

@FunctionalInterface
public interface Reranker {
  Reranker IDENTITY = (query, candidates) -> List.copyOf(candidates);

  List<SearchResult> rerank(String query, List<SearchResult> candidates);
}
