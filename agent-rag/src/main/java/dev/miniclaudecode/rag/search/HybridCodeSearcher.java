package dev.miniclaudecode.rag.search;

import dev.miniclaudecode.rag.chunk.CodeChunk;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class HybridCodeSearcher {
  private final Bm25Retriever bm25;
  private final VectorRetriever vector;
  private final RrfFusion fusion;
  private final Reranker reranker;
  private final CodeQueryRewriter queryRewriter;

  public HybridCodeSearcher(Bm25Retriever bm25, VectorRetriever vector) {
    this(bm25, vector, new RrfFusion(), new CodeAwareReranker(), new CodeQueryRewriter());
  }

  public HybridCodeSearcher(
      Bm25Retriever bm25, VectorRetriever vector, RrfFusion fusion, Reranker reranker) {
    this(bm25, vector, fusion, reranker, new CodeQueryRewriter());
  }

  public HybridCodeSearcher(
      Bm25Retriever bm25,
      VectorRetriever vector,
      RrfFusion fusion,
      Reranker reranker,
      CodeQueryRewriter queryRewriter) {
    this.bm25 = Objects.requireNonNull(bm25, "bm25 must not be null");
    this.vector = Objects.requireNonNull(vector, "vector must not be null");
    this.fusion = Objects.requireNonNull(fusion, "fusion must not be null");
    this.reranker = Objects.requireNonNull(reranker, "reranker must not be null");
    this.queryRewriter = Objects.requireNonNull(queryRewriter, "queryRewriter must not be null");
  }

  public HybridCodeSearcher.SearchResponse search(
      String query, HybridCodeSearcher.SearchOptions options) throws IOException {
    Objects.requireNonNull(options, "options must not be null");
    CodeQueryRewriter.QueryPlan plan = this.queryRewriter.rewrite(query);
    List<RetrievalHit> bm25Hits = new ArrayList<>();
    List<RetrievalHit> vectorHits = new ArrayList<>();
    for (String variant : plan.variants()) {
      bm25Hits.addAll(this.bm25.search(variant, options.candidateLimit()));
      vectorHits.addAll(this.vector.search(variant, options.candidateLimit()));
    }
    bm25Hits = bestPerChunk(bm25Hits);
    vectorHits = bestPerChunk(vectorHits);
    List<SearchResult> fused =
        deduplicateByFile(
            query, this.reranker.rerank(query, this.fusion.fuse(bm25Hits, vectorHits)));
    HybridCodeSearcher.BudgetedSelection selected =
        withinBudget(fused, options.topK(), options.tokenBudget());
    return new HybridCodeSearcher.SearchResponse(
        query,
        selected.results(),
        bm25Hits,
        vectorHits,
        plan.variants(),
        estimatedTokens(selected.results()),
        selected.droppedForBudget());
  }

  private static List<RetrievalHit> bestPerChunk(List<RetrievalHit> hits) {
    Map<String, RetrievalHit> best = new LinkedHashMap<>();
    for (RetrievalHit hit : hits) {
      best.merge(
          hit.chunk().id(),
          hit,
          (left, right) ->
              Comparator.comparingInt(RetrievalHit::rank)
                          .thenComparing(Comparator.comparingDouble(RetrievalHit::score).reversed())
                          .compare(left, right)
                      <= 0
                  ? left
                  : right);
    }
    List<RetrievalHit> ordered =
        best.values().stream()
            .sorted(
                Comparator.comparingInt(RetrievalHit::rank)
                    .thenComparing(Comparator.comparingDouble(RetrievalHit::score).reversed()))
            .toList();
    List<RetrievalHit> reranked = new ArrayList<>(ordered.size());
    for (int index = 0; index < ordered.size(); index++) {
      RetrievalHit hit = ordered.get(index);
      reranked.add(new RetrievalHit(hit.chunk(), hit.score(), index + 1, hit.route()));
    }
    return List.copyOf(reranked);
  }

  private static List<SearchResult> deduplicateByFile(String query, List<SearchResult> candidates) {
    Map<String, SearchResult> selected = new LinkedHashMap<>();
    for (SearchResult candidate : candidates) {
      selected.merge(
          candidate.chunk().path(),
          candidate,
          (current, alternative) ->
              !CodeAwareReranker.isExactSymbolMatch(query, current)
                      && CodeAwareReranker.isExactSymbolMatch(query, alternative)
                  ? alternative
                  : current);
    }
    return List.copyOf(selected.values());
  }

  public HybridCodeSearcher.SearchResponse search(String query) throws IOException {
    return this.search(query, HybridCodeSearcher.SearchOptions.defaults());
  }

  private static HybridCodeSearcher.BudgetedSelection withinBudget(
      List<SearchResult> candidates, int topK, int tokenBudget) {
    List<SearchResult> results = new ArrayList<>();
    int used = 0;
    int dropped = 0;

    for (SearchResult candidate : candidates) {
      if (results.size() == topK) {
        break;
      }

      int tokens = estimatedTokens(candidate.chunk());
      // WHY: an over-budget candidate used to be skipped silently. JavaAstChunker emits a TYPE
      // chunk holding a whole class body, so one oversized chunk could make code_search answer
      // "No relevant code found." while the BM25 hit list was non-empty. Always keep the
      // top-ranked candidate even when it alone busts the budget, and count every other
      // budget skip so SearchResponse can report the truncation instead of hiding it.
      if (results.isEmpty() || used + tokens <= tokenBudget) {
        results.add(candidate);
        used += tokens;
      } else {
        dropped++;
      }
    }

    return new HybridCodeSearcher.BudgetedSelection(List.copyOf(results), dropped);
  }

  private static int estimatedTokens(List<SearchResult> results) {
    return results.stream().mapToInt(result -> estimatedTokens(result.chunk())).sum();
  }

  private static int estimatedTokens(CodeChunk chunk) {
    return Math.max(1, (chunk.content().length() + 3) / 4);
  }

  public static record SearchOptions(int topK, int tokenBudget, int candidateLimit) {
    public SearchOptions(int topK, int tokenBudget, int candidateLimit) {
      if (topK >= 1 && tokenBudget >= 1 && candidateLimit >= topK) {
        this.topK = topK;
        this.tokenBudget = tokenBudget;
        this.candidateLimit = candidateLimit;
      } else {
        throw new IllegalArgumentException("invalid search options");
      }
    }

    public static HybridCodeSearcher.SearchOptions defaults() {
      return new HybridCodeSearcher.SearchOptions(8, 6000, 40);
    }
  }

  /** Selection outcome: what survived the token budget, and how much the budget threw away. */
  private static record BudgetedSelection(List<SearchResult> results, int droppedForBudget) {}

  // WHY droppedForBudget: token-budget truncation used to be invisible to callers, so a short or
  // empty result list looked identical to "the index has nothing". Callers can now say how many
  // ranked candidates were cut for budget reasons.
  public static record SearchResponse(
      String query,
      List<SearchResult> results,
      List<RetrievalHit> bm25Hits,
      List<RetrievalHit> vectorHits,
      List<String> queryVariants,
      int estimatedTokens,
      int droppedForBudget) {
    public SearchResponse(
        String query,
        List<SearchResult> results,
        List<RetrievalHit> bm25Hits,
        List<RetrievalHit> vectorHits,
        List<String> queryVariants,
        int estimatedTokens,
        int droppedForBudget) {
      query = Objects.requireNonNullElse(query, "");
      results = List.copyOf(results);
      bm25Hits = List.copyOf(bm25Hits);
      vectorHits = List.copyOf(vectorHits);
      queryVariants = List.copyOf(queryVariants);
      this.query = query;
      this.results = results;
      this.bm25Hits = bm25Hits;
      this.vectorHits = vectorHits;
      this.queryVariants = queryVariants;
      this.estimatedTokens = estimatedTokens;
      this.droppedForBudget = droppedForBudget;
    }

    public String explain() {
      StringBuilder output = new StringBuilder();
      output
          .append("query: ")
          .append(this.query)
          .append('\n')
          .append("query variants: ")
          .append(String.join(" | ", this.queryVariants))
          .append('\n')
          .append("BM25 candidates: ")
          .append(this.bm25Hits.size())
          .append(", vector candidates: ")
          .append(this.vectorHits.size())
          .append('\n');
      if (this.droppedForBudget > 0) {
        output.append("dropped for token budget: ").append(this.droppedForBudget).append('\n');
      }

      for (int index = 0; index < this.results.size(); index++) {
        SearchResult result = this.results.get(index);
        output
            .append(index + 1)
            .append(". ")
            .append(result.chunk().path())
            .append(':')
            .append(result.chunk().startLine())
            .append(" ")
            .append(result.chunk().symbol())
            .append(" [")
            .append(result.explanation())
            .append("]\n");
      }

      return output.toString().stripTrailing();
    }

    /**
     * Re-applies context packing after a child match is expanded to its parent document section.
     */
    public HybridCodeSearcher.SearchResponse withContext(
        List<SearchResult> contextResults, int tokenBudget) {
      HybridCodeSearcher.BudgetedSelection selected =
          HybridCodeSearcher.withinBudget(
              contextResults, Math.max(1, this.results.size()), tokenBudget);
      return new HybridCodeSearcher.SearchResponse(
          this.query,
          selected.results(),
          this.bm25Hits,
          this.vectorHits,
          this.queryVariants,
          HybridCodeSearcher.estimatedTokens(selected.results()),
          this.droppedForBudget + selected.droppedForBudget());
    }
  }
}
