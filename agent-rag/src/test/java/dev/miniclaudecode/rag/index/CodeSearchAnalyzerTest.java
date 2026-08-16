package dev.miniclaudecode.rag.index;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class CodeSearchAnalyzerTest {

  @Test
  void chineseBecomesOverlappingBigramsRatherThanSingleCharacters() throws IOException {
    // Single-character terms match nearly every Chinese chunk in a corpus, which is why the
    // benchmark's Chinese split sat at Recall@5 0.077 while English scored 0.923.
    Assertions.assertThat(analyze("会话恢复")).containsExactly("会话", "话恢", "恢复");
  }

  @Test
  void aLoneChineseCharacterStillProducesASearchableTerm() throws IOException {
    Assertions.assertThat(analyze("图")).containsExactly("图");
  }

  @Test
  void latinTextIsTokenizedAndLowercasedExactlyAsBefore() throws IOException {
    Assertions.assertThat(analyze("SessionStore.load(sessionId)"))
        .containsExactly("sessionstore.load", "sessionid");
  }

  @Test
  void fullWidthLatinFoldsOntoItsHalfWidthForm() throws IOException {
    Assertions.assertThat(analyze("ＵＰＬＯＡＤ")).containsExactly("upload");
  }

  @Test
  void codeKeywordsSurviveBecauseThereIsNoStopWordList() throws IOException {
    // English stop lists drop if/for/do/in/to, all of which are keywords or method names someone
    // may legitimately search for in source code.
    Assertions.assertThat(analyze("if for do in to"))
        .containsExactly("if", "for", "do", "in", "to");
  }

  @Test
  void mixedChineseAndLatinKeepsBothSides() throws IOException {
    Assertions.assertThat(analyze("重建 index")).containsExactly("重建", "index");
  }

  private static List<String> analyze(String value) throws IOException {
    List<String> terms = new ArrayList<>();
    try (CodeSearchAnalyzer analyzer = new CodeSearchAnalyzer();
        TokenStream tokens = analyzer.tokenStream("search_text", value)) {
      CharTermAttribute term = tokens.addAttribute(CharTermAttribute.class);
      tokens.reset();
      while (tokens.incrementToken()) {
        terms.add(term.toString());
      }
      tokens.end();
    }
    return List.copyOf(terms);
  }
}
