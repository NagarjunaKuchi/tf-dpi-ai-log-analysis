# Performance considerations

**No load or performance test has been run.** Numbers below are design limits and starting points, not measurements. Measure on your data.

## Bounded by design
| Resource | Limit |
|---|---|
| Concurrent analyses | `dpi.analysis.threads` (4) running + `dpi.analysis.queue` (32) waiting; beyond that HTTP 503 |
| LLM retries | 2 attempts, ≤5 s backoff (Spring AI settings); agent loops ≤ 2 retrieval + 2 verification retries; wall-clock check between nodes (120 s) |
| Embedding | batch size 32, 2 threads, queue 8, per-call timeout 30 s |
| Ingestion | batch size and batches/run in `dpi.ingestion.*`; one writer cluster-wide |
| Live ES query tool | ≤1 h window, ≤50 events |
| Vector search | topK 8 (logs) / 3 (knowledge) |

## Sizing starting points (assumptions)
- Vectors: `vector(1024)` ≈ 4 KB raw per row plus text/metadata and HNSW overhead (commonly 1.5–3× the raw vector size; verify). Estimate chunks/day × retention before choosing disk.
- HNSW build/memory cost grows with row count; at tens of millions of rows consider partitioning by time and shorter retention, and tune `m` / `ef_construction` / `hnsw.ef_search` against recall measurements (not tuned here; defaults used).
- JVM: container-aware heap (`MaxRAMPercentage=70`) with 2 Gi limit as the chart default; the jar is ~166 MB. Raise memory if analyses carry large evidence sets.
- The LLM dominates latency and cost. Expect an analysis to take as long as 2–4 sequential model calls (analyze, [rewrite], root cause, possibly again). GPU throughput of the chat model is the real capacity limit — size `dpi.analysis.threads` to it, not to CPU.
- Ingestion lag is bounded by embedding throughput; watch `dpi_ingestion_lag_seconds` and `dpi_embeddings_latency`.

## Known performance gaps
- Timers are summaries (no percentile histograms); p95/p99 not available without enabling histograms.
- No caching of embeddings for repeated questions or of retrieval results.
- Retrieval is vector-only (no hybrid keyword search); exact error codes rely on metadata filters.
- Index and query plans were not examined with `EXPLAIN` on realistic volumes.
