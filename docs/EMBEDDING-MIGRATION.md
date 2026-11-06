# Embedding model migration (manual procedure — NOT automated)

Every vector row stores `embeddingModel` and `embeddingVersion`; every retrieval filters on the configured pair, so vectors from
different models are never compared. Changing `dpi.embedding.model-name` / `model-version` therefore makes **all existing vectors
invisible** until they are re-embedded. The README matrix lists automated re-embedding as pending.

Procedure (no code changes, brief downtime of retrieval quality):
1. Pick the new model; confirm its dimension. If it is not 1024, a new Flyway migration must recreate the `vector(1024)` columns and HNSW indexes, and `dpi.embedding.dimensions` must change in the same release.
2. Deploy with the new `DPI_EMBEDDING_MODEL` / `DPI_EMBEDDING_MODEL_VERSION`. New log ingestion starts writing new-model vectors immediately (the chunk id is deterministic, so already-ingested chunks are re-embedded only if you reset the checkpoint).
3. Logs: reset the ingestion checkpoint (`DELETE FROM ingestion_checkpoint WHERE stream_name = '<stream>'`) to re-read history from Elasticsearch within `dpi.ingestion.lookback`; old-model rows age out through retention (or delete them once validated).
4. Knowledge: re-submit each document through `POST /api/v1/knowledge`. Unchanged content is a no-op by design, so first bump content (or add a one-off tool) — **this is the gap that automation must close**; until then re-embedding knowledge needs a small script that reads `knowledge_vectors.content` and rewrites the rows.
5. Validate with a set of known questions before deleting old vectors. Roll back by restoring the previous configuration (old vectors are untouched until deleted).
