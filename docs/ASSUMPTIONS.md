# Assumptions and design decisions

The presentation ("AI-POC-DPITeam_Enhanced.pptx") is the source of truth. Where it is silent, the choice below
is recorded here. None of these changes the approved architecture.

| # | Topic | Decision | Reason / source |
|---|-------|----------|-----------------|
| 1 | Package root | `tf.dpi.ai` (Maven groupId `tf.dpi.ai`) | Requested by the project owner |
| 2 | Java / Boot | Java 21, **Spring Boot 4.0.x** (deviation from the brief's "3.x") | Project owner chose Spring AI 2.0.2, which requires Boot 4; Boot 3.x cannot load it. Deck only says "Spring Boot 3.x" generically |
| 3 | Build tool | Maven | Deck says "Maven / Gradle"; the brief says Maven |
| 4 | Embedding dimension | 1024 (BGE-M3) | **Unverified**: confirm against the deployed model before first migration |
| 5 | MOSIP identifiers | UIN = 10 digits, VID = 16 digits; bare numbers of those lengths are masked | Not in the deck. Over-masks some epoch-second timestamps and phone numbers on purpose |
| 6 | Token estimate | ~4 characters per token | Heuristic; avoids a tokenizer dependency. Budget leaves headroom below the model limit |
| 7 | Grouping key | trace id if present, else service + host + (error code or exception type) | Deck: "by service, trace and time" and "service, code or time" |
| 8 | Runbook trust | Documents default to `UNVERIFIED`; only `ENGINEER_VALIDATED` documents may be cited as established fixes | Brief: unverified AI-generated resolutions are not established fixes |
| 9 | Stack-trace overflow | A single event over budget keeps all exception headers and "Caused by" lines, elides deep frames, flags `truncated=true` | Brief: keep chains whole and avoid oversized chunks |
| 10 | Checkpoint | Stored in PostgreSQL with the `search_after` tuple of the last record, committed in the same transaction as the vectors and dead letters | Brief: never advance beyond persisted records |
| 11 | "Persisted" includes durably dead-lettered | A record that is unmappable, cannot be masked safely, or yields an unusable embedding is written to `ingestion_dead_letter` in the same transaction, after which the checkpoint may pass it. Dependency outages dead-letter nothing and abort the run | Otherwise one poison record would block ingestion forever, while the brief forbids silently discarding records |
| 12 | Vector storage layout | Tables follow Spring AI `PgVectorStore` (`id, content, metadata jsonb, embedding`); reads go through `VectorStore`, writes use JDBC with precomputed embeddings | Embedding must not happen inside the DB transaction; the brief requires `VectorStore` for retrieval |
| 13 | Sort tiebreaker | `_id` (configurable `dpi.elasticsearch.tiebreaker-field`); no point-in-time API is opened, so the client only issues `search` | **Unverified**: whether `_id` sorting is enabled on your cluster. Set a unique keyword field if not |
| 14 | Source field names | Flat Logstash names (`@timestamp`, `service`, `host`, `level`, `message`, `traceId`, `errorCode`, `stack_trace`), all configurable | Real MOSIP index mapping not provided |
| 15 | Ingestion scope | Anchors are WARN and above; INFO/DEBUG enter only as context within +/-30 s of an anchor (same trace, else same service+host) | Deck: "WARN/ERROR events with surrounding context" |
| 16 | Concurrency | One ingestion run per stream (cluster-wide via a PostgreSQL advisory lock); embedding calls use a bounded executor (2 threads, queue 8) | Pagination is inherently sequential; the brief asks for bounded concurrency, not parallel pagination |
| 17 | Identifier fields | If masking would alter a trace id, service, host, error code or exception type, it is replaced by a stable hash instead | Keeps grouping correct (distinct values never collapse to one placeholder) |
| 18 | Overlapping chunks | Context lookups can pull events that a later run also ingests as anchors, producing overlapping chunks with different ids | Accepted; retrieval can dedupe by source ids |
