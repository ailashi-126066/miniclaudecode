package dev.miniclaudecode.rag.search;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.miniclaudecode.rag.index.LuceneCodeIndex;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

public final class VectorRetriever {
  private final Path indexDirectory;
  private final EmbeddingModel embeddingModel;

  public VectorRetriever(Path indexDirectory, EmbeddingModel embeddingModel) {
    this.indexDirectory = indexDirectory.toAbsolutePath().normalize();
    this.embeddingModel = Objects.requireNonNull(embeddingModel, "embeddingModel must not be null");
  }

  public List<RetrievalHit> search(String queryText, int limit) throws IOException {
    if (queryText != null
        && !queryText.isBlank()
        && limit >= 1
        && Files.isDirectory(this.indexDirectory)) {
      float[] vector =
          (float[]) ((Embedding) this.embeddingModel.embed(queryText).content()).vector().clone();
      normalize(vector);
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
            TopDocs topDocs =
                new IndexSearcher(reader)
                    .search(new KnnFloatVectorQuery("vector", vector, limit), limit);
            List<RetrievalHit> hits = new ArrayList<>(topDocs.scoreDocs.length);
            int rank = 1;

            for (ScoreDoc scored : topDocs.scoreDocs) {
              Document document = reader.storedFields().document(scored.doc);
              hits.add(
                  new RetrievalHit(
                      LuceneCodeIndex.storedChunk(document),
                      (double) scored.score,
                      rank++,
                      RetrievalRoute.VECTOR));
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

  private static void normalize(float[] vector) {
    if (vector.length == 0) {
      throw new IllegalStateException("embedding model returned an empty vector");
    } else {
      double sum = 0.0;

      for (float value : vector) {
        sum += (double) (value * value);
      }

      if (sum == 0.0) {
        vector[0] = 1.0F;
      } else {
        double magnitude = Math.sqrt(sum);

        for (int index = 0; index < vector.length; index++) {
          vector[index] = (float) ((double) vector[index] / magnitude);
        }
      }
    }
  }
}
