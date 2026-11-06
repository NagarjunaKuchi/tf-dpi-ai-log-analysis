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

## Phase 4 assumptions (retrieval, knowledge, agent)

19. LangGraph4j state is serialised with Java serialisation by default, so all state-held records implement `Serializable`. A future PostgreSQL checkpoint saver must not deserialise rows an attacker can write; restrict DB write access to the service account.
20. The relevance grader and evidence verifier are deterministic (no LLM) so they are reproducible and testable. The LLM is used only for query analysis, query rewriting and root-cause analysis.
21. The LLM never selects tools or tool arguments; the graph calls `vectorSearch`, `runbookSearch` and `esQuery` directly. `esQuery` is read-only, allowlisted, window- and size-bounded, and masked.
22. Scenario regexes (DB connection refused, OOM, ActiveMQ) are generic JVM/JMS/JDBC signatures, not tuned to real MOSIP/DPI logs; they must be validated against production samples.
23. A finding must quote terms verbatim present in its cited evidence; this catches fabricated citations but cannot detect a correct quote used to support a wrong conclusion. Human review remains the final gate.
24. Knowledge documents are UNVERIFIED until an engineer validates them; UNVERIFIED knowledge is still retrievable but flagged in the report. Change this if policy requires validated-only retrieval.
25. Retrieval similarity thresholds (0.5 search, 0.6 grading) are placeholder defaults that need tuning on real data.

## Phase 5 assumptions (API and security)

26. The IdP issues JWTs with a string-array `roles` claim (configurable via `dpi.security.rolesClaim`) containing ANALYST, REVIEWER, KNOWLEDGE_ADMIN or OPERATOR. Keycloak-style nested `realm_access.roles` is not handled.
27. The application refuses to start without `DPI_JWT_ISSUER_URI` (fail closed). Startup also contacts the issuer for key discovery; behaviour when it is down is unverified.
28. Rate limiting is per instance (in-memory token bucket), so the effective cluster limit scales with replica count.
29. Any authenticated ANALYST/REVIEWER can read any analysis; there is no per-owner or per-tenant isolation.
30. A reviewer may approve their own submitted analysis; separation of duties is not enforced.
31. A Jackson 2 `ObjectMapper` bean is declared for JDBC JSON and the ES client because Boot 4's MVC mapper is Jackson 3; two executor beans of the same type rely on parameter-name injection. Both unverified until the context is started.
32. In-flight analyses are lost on restart (status stays RUNNING); there is no recovery job yet.
