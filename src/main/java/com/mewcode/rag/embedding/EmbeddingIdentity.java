package com.mewcode.rag.embedding;

/**
 * Stable identity of an embedding vector space.
 *
 * <p>Vectors from different models — or the same model at a different dimension — are not
 * comparable, and Lucene refuses mixed dimensions on one vector field. The index persists this
 * identity and forces a full rebuild when it changes, so switching providers can never silently mix
 * incompatible vectors.
 */
public interface EmbeddingIdentity {
  String embeddingIdentity();
}
