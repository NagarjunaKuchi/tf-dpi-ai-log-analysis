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
| 13 | Sort tiebreaker | `_id` (configurable `dpi.elasticsearch.tiebreaker-field`); no point-in-time API is opened, so the client only issues `search` | **Known problem, not yet fixed**: Elasticsearch 8.x disables fielddata on `_id` by default (`indices.id_field_data.enabled=false`; Elastic PR 49166 and user reports), so paging fails with "Fielddata access on the _id field is disallowed" unless a cluster admin enables it. The sampled documents have no other unique sortable field. Not yet run against a real cluster |
| 14 | Source field names | Defaults taken from two real MOSIP/DPI documents: `@timestamp`, `level`, `message`, `traceId`, `kubernetes.container_name` (service), `kubernetes.pod_name` (host); error code and stack trace are derived from `message`; filters use the `.keyword` sub-fields. All configurable | Only two sample documents (an exception and a plain error) were seen; other services' shapes are unverified |
| 15 | Ingestion scope | Anchors are WARN and above; INFO/DEBUG enter only as context within +/-30 s of an anchor (same trace, else same service+host) | Deck: "WARN/ERROR events with surrounding context" |
| 16 | Concurrency | One ingestion run per stream (cluster-wide via a PostgreSQL advisory lock); embedding calls use a bounded executor (2 threads, queue 8) | Pagination is inherently sequential; the brief asks for bounded concurrency, not parallel pagination |
| 17 | Identifier fields | If masking would alter a trace id, service, host, error code or exception type, it is replaced by a stable hash instead | Keeps grouping correct (distinct values never collapse to one placeholder) |
| 18 | Overlapping chunks | Context lookups can pull events that a later run also ingests as anchors, producing overlapping chunks with different ids | Accepted; retrieval can dedupe by source ids |

## Phase 4 assumptions (retrieval, knowledge, agent)

19. LangGraph4j state is serialised with Java serialisation by default, so all state-held records implement `Serializable`. A future PostgreSQL checkpoint saver must not deserialise rows an attacker can write; restrict DB write access to the service account.
20. The relevance grader and evidence verifier are deterministic (no LLM) so they are reproducible and testable. The LLM is used only for query analysis, query rewriting and root-cause analysis.
21. The LLM never selects tools or tool arguments; the graph calls `vectorSearch`, `runbookSearch` and `esQuery` directly. `esQuery` is read-only, allowlisted, window- and size-bounded, and masked.
22. Failure signatures are hints and a guard against mislabelled findings, not a list of supported errors: any error can be analysed and gets a model-chosen label. The built-in regexes (DB connection refused, OOM, ActiveMQ, upstream reset, timeout, DNS, disk full, TLS, auth, NPE) are generic JVM/infrastructure patterns, tested only against one synthetic example each, not tuned to real MOSIP/DPI logs; add or replace them with `dpi.agent.signatures.custom`. A finding labelled with a signature name is rejected when that signature is absent from its cited current evidence, so an over-narrow regex rejects correct findings carrying that label.
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
32. In-flight analyses are lost on restart. `StaleAnalysisRecovery` marks QUEUED/RUNNING rows idle for longer than 2x agent timeout + 1 min as FAILED/INTERRUPTED (at startup and every 5 min); the caller must resubmit. Runs are not resumed.

## Phase 6 notes

33. The agent timeout is checked between nodes only; one LLM call can overrun it by at most the capped Spring AI retry window plus the model's own read timeout. A hard per-call deadline is not implemented.
34. Integration tests use pgvector 0.6.0; production should be validated on the actual pgvector version (HNSW options differ across releases).
35. Tests use a deterministic hashing embedding model; they prove plumbing and filters, not semantic retrieval quality with BGE-M3.
36. End-to-end scenarios use a scripted LLM, so they prove the pipeline, the verifier and the report contract, not how a real model behaves (it may produce malformed structured output, which ends in a FAILED outcome).
37. No distributed tracing is configured.
38. Keycloak (26.8.2) is the reference identity provider. The dev realm is import-only (no users, no passwords); user and role provisioning, password policy, MFA and account lifecycle are operated in Keycloak, not in this service.
39. Tokens must carry `aud` = `DPI_JWT_AUDIENCE`. Keycloak does not add the client id to `aud` on its own; the imported realm has an audience mapper. A realm without it will get 401 for every request.
40. `iss` and key retrieval are configured separately because in containers the token's `iss` (public/`localhost` hostname) is usually not the address the service uses to reach Keycloak. Only RS256 is accepted.
41. Access tokens are validated offline (signature + claims); there is no revocation check or introspection. A disabled user keeps access until the token expires (15 min in the dev realm). Short token lifetimes are the mitigation.
42. Roles are read from `roles`, `realm_access.roles` and `resource_access.<audience>.roles` and must match `[A-Z_]{2,40}`; a role mapped in any other claim is ignored.
43. The dev Keycloak (`start-dev`, H2, HTTP) is for development only; the Compose/Terraform/Helm wiring for it has been statically validated but not run (no Docker daemon in the authoring environment).
44. One deployment serves one application (decided with the owner). Multi-application tenancy in a single instance (source ids, per-source state and isolation) is not implemented.
45. Mapping is configuration first (`fields`, `mapping`), with a `LogEventMapper` bean as the escape hatch. Tested with synthetic documents only, never with another application's real logs. An unrecognised log level maps to INFO and is dropped by the default WARN filter without a report.
46. `mapping.exception-pattern` is an operator-supplied regular expression; it is limited to the first 1000 characters of a trace, but a pathological pattern could still be slow. Treat it as trusted configuration.
47. The two sampled documents are the only evidence for the default mapping; a service that logs differently (JSON in `message`, no Java frames, other error-code formats) needs the `mapping` settings or a custom `LogEventMapper`. The error-code default pattern also matches any `AAA-BBB-123` shaped text, not only MOSIP codes.
48. **Registration IDs are not masked.** The default rules mask bare 10- and 16-digit UIN/VID and key=value forms, so the 29-digit registration id in `... REGISTRATIONID - <id> - ...` reaches the embedding model and the vector store unchanged. Whether to mask or pseudonymise it is an open decision (masking removes the ability to follow one registration across services).
