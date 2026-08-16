package dev.miniclaudecode.rag.index;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.cjk.CJKBigramFilter;
import org.apache.lucene.analysis.cjk.CJKWidthFilter;
import org.apache.lucene.analysis.standard.StandardTokenizer;

/**
 * The single analyzer used for both indexing and querying.
 *
 * <p>It replaces {@code StandardAnalyzer}, which tokenizes CJK one character at a time. Unigram
 * Chinese is why the benchmark's Chinese split scored Recall@5 0.077 against 0.923 for English: a
 * query like 会话恢复 became four independent single-character terms, each of which matches almost
 * every Chinese chunk in the corpus, so BM25 had no discriminating signal left. {@link
 * CJKBigramFilter} turns the same query into overlapping bigrams (会话, 话恢, 恢复), which behave like
 * words.
 *
 * <p>Latin text is unaffected: the bigram filter only rewrites Han, Hiragana, Katakana and Hangul
 * tokens and passes everything else through. {@link CJKWidthFilter} folds full-width Latin and
 * half-width Katakana first, so ＵＰＬＯＡＤ and UPLOAD agree.
 *
 * <p>No stop words, deliberately. English stop lists remove {@code if}, {@code for}, {@code do},
 * {@code in} and {@code to} — all keywords or method names in source code, and all things a user
 * may legitimately search for.
 *
 * <p>Changing anything here changes the terms already written to disk, so {@link
 * LuceneCodeIndex#SCHEMA_VERSION} must be bumped in the same commit to force a rebuild. An index
 * written by one analyzer and queried by another silently returns nothing.
 */
public final class CodeSearchAnalyzer extends Analyzer {

  @Override
  protected TokenStreamComponents createComponents(String fieldName) {
    StandardTokenizer source = new StandardTokenizer();
    TokenStream tokens = new CJKWidthFilter(source);
    tokens = new LowerCaseFilter(tokens);
    tokens = new CJKBigramFilter(tokens);
    return new TokenStreamComponents(source, tokens);
  }
}
