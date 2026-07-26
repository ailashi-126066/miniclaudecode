package dev.miniclaudecode.rag.search;

import dev.miniclaudecode.rag.index.LuceneCodeIndex;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery.Builder;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

public final class Bm25Retriever {
  private final Path indexDirectory;

  public Bm25Retriever(Path indexDirectory) {
    this.indexDirectory = indexDirectory.toAbsolutePath().normalize();
  }

  public List<RetrievalHit> search(String queryText, int limit) throws IOException {
    if (queryText != null
        && !queryText.isBlank()
        && limit >= 1
        && Files.isDirectory(this.indexDirectory)) {
      Directory directory = FSDirectory.open(this.indexDirectory);

      List var18;
      label86:
      {
        List var19;
        try {
          if (!DirectoryReader.indexExists(directory)) {
            var18 = List.of();
            break label86;
          }

          DirectoryReader reader = DirectoryReader.open(directory);

          try {
            Query query = query(queryText);
            TopDocs topDocs = new IndexSearcher(reader).search(query, limit);
            List<RetrievalHit> hits = new ArrayList<>(topDocs.scoreDocs.length);
            int rank = 1;

            for (ScoreDoc scored : topDocs.scoreDocs) {
              Document document = reader.storedFields().document(scored.doc);
              hits.add(
                  new RetrievalHit(
                      LuceneCodeIndex.storedChunk(document),
                      (double) scored.score,
                      rank++,
                      RetrievalRoute.BM25));
            }

            var19 = List.copyOf(hits);
          } catch (Throwable var16) {
            if (reader != null) {
              try {
                reader.close();
              } catch (Throwable var15) {
                var16.addSuppressed(var15);
              }
            }

            throw var16;
          }

          if (reader != null) {
            reader.close();
          }
        } catch (Throwable var17) {
          if (directory != null) {
            try {
              directory.close();
            } catch (Throwable var14) {
              var17.addSuppressed(var14);
            }
          }

          throw var17;
        }

        if (directory != null) {
          directory.close();
        }

        return var19;
      }

      if (directory != null) {
        directory.close();
      }

      return var18;
    } else {
      return List.of();
    }
  }

  private static Query query(String value) throws IOException {
    List<String> terms = analyze(value);
    if (terms.isEmpty()) {
      throw new IllegalArgumentException("query must contain searchable terms");
    } else {
      Builder query = new Builder();

      for (String term : terms) {
        query.add(new TermQuery(new Term("search_text", term)), Occur.SHOULD);
        query.add(new BoostQuery(new TermQuery(new Term("symbol_text", term)), 3.0F), Occur.SHOULD);
        query.add(new BoostQuery(new TermQuery(new Term("path_text", term)), 1.8F), Occur.SHOULD);
      }

      query.setMinimumNumberShouldMatch(1);
      return query.build();
    }
  }

  private static List<String> analyze(String value) throws IOException {
    List<String> terms = new ArrayList<>();
    try (StandardAnalyzer analyzer = new StandardAnalyzer();
        TokenStream tokens = analyzer.tokenStream("query", value)) {
      CharTermAttribute term = tokens.addAttribute(CharTermAttribute.class);
      tokens.reset();

      while (tokens.incrementToken()) {
        terms.add(term.toString());
      }

      tokens.end();
    }

    return terms;
  }
}
