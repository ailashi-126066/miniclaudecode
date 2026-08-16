package dev.miniclaudecode.rag.search;

import static org.assertj.core.api.Assertions.assertThat;

import dev.miniclaudecode.rag.chunk.CodeChunk;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CodeAwareRerankerTest {
  private final CodeAwareReranker reranker = new CodeAwareReranker();

  @Test
  void exactSymbolMatchOutranksIncidentalContentMention() {
    SearchResult incidental =
        result(
            "src/other/Helper.java",
            "formatOutput",
            "// see parseRequest for details; parseRequest is documented elsewhere",
            0.9);
    SearchResult exactSymbol =
        result(
            "src/http/RequestParser.java",
            "parseRequest",
            "public Request parseRequest(String raw) { return new Request(raw); }",
            0.1);

    List<SearchResult> ordered =
        this.reranker.rerank("parseRequest", List.of(incidental, exactSymbol));

    assertThat(ordered.get(0).chunk().symbol()).isEqualTo("parseRequest");
    assertThat(ordered).containsExactly(exactSymbol, incidental);
  }

  @Test
  void ignoresStopWordsAndDoesNotMatchInsideLargerTokens() {
    SearchResult substring =
        result(
            "src/main/java/example/Concatenator.java",
            "concatenate",
            "String concatenate(String left, String right) { return left + right; }",
            0.2);
    SearchResult exact =
        result("src/main/java/example/Cat.java", "cat", "String cat() { return \"cat\"; }", 0.2);

    List<SearchResult> ordered =
        this.reranker.rerank("where is the cat", List.of(substring, exact));

    assertThat(ordered).containsExactly(exact, substring);
  }

  @Test
  void fusedScoreBreaksLexicallyEquivalentCandidates() {
    SearchResult weakFusion =
        result("src/main/java/example/First.java", "restore", "void restore() {}", 0.1);
    SearchResult strongFusion =
        result("src/main/java/example/Second.java", "restore", "void restore() {}", 0.9);

    assertThat(this.reranker.rerank("restore", List.of(weakFusion, strongFusion)))
        .containsExactly(strongFusion, weakFusion);
  }

  @Test
  void prefersProductionByDefaultAndTestsForExplicitTestQueries() {
    SearchResult production =
        result(
            "module/src/main/java/example/RestoreService.java",
            "restoreSession",
            "void restoreSession() {}",
            0.2);
    SearchResult test =
        result(
            "module/src/test/java/example/RestoreServiceTest.java",
            "restoreSession",
            "void restoreSession() {}",
            0.2);

    assertThat(this.reranker.rerank("restore session implementation", List.of(test, production)))
        .containsExactly(production, test);
    assertThat(this.reranker.rerank("restore session test", List.of(production, test)))
        .containsExactly(test, production);
  }

  @Test
  void productionAndTestLayoutsAreRecognisedOutsideMaven() {
    // Only `src/main/` used to count as production, so in a Python, Go or JS repository nothing
    // could earn the boost while `tests/` still earned the penalty — the signal could only
    // subtract.
    assertThat(
            this.reranker.rerank(
                "restore session implementation",
                List.of(
                    result(
                        "tests/test_restore.py", "restore_session", "def restore_session():", 0.2),
                    result("src/restore.py", "restore_session", "def restore_session():", 0.2))))
        .extracting(candidate -> candidate.chunk().path())
        .containsExactly("src/restore.py", "tests/test_restore.py");

    assertThat(
            this.reranker.rerank(
                "restore session implementation",
                List.of(
                    result("internal/store/restore_test.go", "RestoreSession", "func x() {}", 0.2),
                    result("internal/store/restore.go", "RestoreSession", "func x() {}", 0.2))))
        .extracting(candidate -> candidate.chunk().path())
        .containsExactly("internal/store/restore.go", "internal/store/restore_test.go");

    assertThat(
            this.reranker.rerank(
                "runner test",
                List.of(
                    result("src/runner.ts", "start", "async start() {}", 0.2),
                    result("src/__tests__/runner.spec.ts", "start", "async start() {}", 0.2))))
        .extracting(candidate -> candidate.chunk().path())
        .containsExactly("src/__tests__/runner.spec.ts", "src/runner.ts");
  }

  @Test
  void chineseQueriesMatchOnBigramsInsteadOfWholePhrasesOnly() {
    // Punctuation splitting leaves a Chinese phrase as one token, so exact-token equality only
    // fired on an identical phrase and the 0.52 lexical weight contributed nothing.
    SearchResult related =
        result("src/main/java/example/Restore.java", "restore", "会话恢复失败时重放事件", 0.2);
    SearchResult unrelated =
        result("src/main/java/example/Index.java", "index", "构建向量索引并写入磁盘", 0.2);

    assertThat(this.reranker.rerank("会话恢复", List.of(unrelated, related)))
        .containsExactly(related, unrelated);
  }

  @Test
  void fullyQualifiedSymbolLookupPrefersTheExactMethodOverItsClassSkeleton() {
    SearchResult type =
        result(
            "src/main/java/example/RestoreService.java",
            "RestoreService",
            "class RestoreService { void restoreSession(String id); }",
            0.3);
    SearchResult method =
        result(
            "src/main/java/example/RestoreService.java",
            "restoreSession(String)",
            "void restoreSession(String id) {}",
            0.2);

    assertThat(this.reranker.rerank("RestoreService.restoreSession(String)", List.of(type, method)))
        .startsWith(method);
    assertThat(this.reranker.rerank("RestoreService.restoreSession", List.of(type, method)))
        .startsWith(method);
  }

  private static SearchResult result(
      String path, String symbol, String content, double fusedScore) {
    CodeChunk chunk =
        CodeChunk.create(
            path, "java", CodeChunk.Kind.METHOD, "dev.example", "Owner", symbol, 1, 5, content);
    return new SearchResult(chunk, fusedScore, Map.of(), Map.of());
  }
}
