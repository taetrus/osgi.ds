package com.kk.pde.ds.rag.api;

import java.util.List;

/**
 * Stores chunk embeddings and answers nearest-neighbour queries.
 *
 * <p>Deliberately minimal so the initial in-memory, brute-force implementation
 * can later be swapped for a real vector database (e.g. pgvector) without
 * touching callers.</p>
 */
public interface VectorStore {

	/** Add a chunk and its embedding to the store. */
	void add(Chunk chunk, float[] embedding);

	/**
	 * Add several chunks and their embeddings as one batch. {@code chunks} and
	 * {@code embeddings} must be the same length and are paired by index. Adding a
	 * document's chunks in a single call keeps the store consistent (no half-ingested
	 * document is ever visible) and avoids the per-element copies a naive store pays.
	 */
	void addAll(List<Chunk> chunks, List<float[]> embeddings);

	/**
	 * Remove every chunk previously stored for the given source document.
	 * Called before re-ingesting a document so a second ingest replaces rather than
	 * duplicates its chunks. Returns the number of chunks removed.
	 */
	int removeBySource(String source);

	/**
	 * Return the {@code topK} chunks most similar to the query embedding,
	 * highest score first.
	 */
	List<ScoredChunk> search(float[] queryEmbedding, int topK);

	/** Number of chunks currently stored. */
	int size();

	/** Remove everything. */
	void clear();
}
