# DPI Agentic RAG Log Analysis (`tf.dpi.ai`)

Agentic RAG incident analysis on top of the existing Logstash → Elasticsearch → Kibana pipeline.
Architecture source of truth: *AI-POC-DPITeam_Enhanced.pptx* (Spring Boot + Spring AI, PostgreSQL + pgvector,
LangGraph4j, self-hosted Ollama/vLLM, engineer validation as the final gate).

## Status: Phases 2-3 in progress — NOT production ready

`mvn test`: 79 tests pass, 6 skipped (Testcontainers/pgvector tests need Docker, which the authoring environment
lacks). Stack: Spring Boot 4.0.9, Spring AI 2.0.2, Java 21, Elasticsearch client 8.19.24.

| Component | State | Verification |
|-----------|-------|--------------|
| Build / dependencies | Done | Resolves and compiles (`docs/VERSIONS.md`) |
| Masking (12 categories, JSON-aware, fail-closed) + event sanitizer | Done | 24 unit tests |
| Log grouping and chunking | Done | 11 unit tests |
| Elasticsearch module (client+TLS+auth, query builder, mapper, read-only reader with bounded retry, health) | Done | 22 unit tests with a mocked client. **Not run against a real cluster** |
| Embedding service (Spring AI `EmbeddingModel`, batching, timeout, dimension check, duplicate guard) | Done | 8 unit tests with a mocked model. **Not run against Ollama/vLLM** |
| Incremental ingestion (checkpoint, dead letter, retry, atomic commit, graceful stop) | Done | 14 unit tests with fakes |
| JDBC store, Flyway schema, retention job, advisory-lock scheduler | Written | 6 integration tests written but **never executed**; SQL never run on PostgreSQL |
| Spring application context / wiring | Written | **Never started** (needs PostgreSQL); `spring.ai.model.*` selection keys unverified |
| Embedding model migration (re-embed on version change) | Pending | |
| Runbook / incident knowledge ingestion (Module 7) | Implemented, unit-tested (chunker only) | Versioning, masking, UNVERIFIED-by-default, atomic supersession. SQL never run on a real PostgreSQL; no HTTP endpoint yet (Phase 5) |
| Vector retrieval service (Spring AI `VectorStore`, filters) | Implemented, compiles | `VectorStoreDocumentRetriever` + metadata filters pinned to embedding model/version. Filter-to-SQL translation unverified (no Docker) |
| LangGraph4j agent, nodes, tools (Modules 8-9) | Implemented, 6 graph tests with a fake LLM | 9 nodes, bounded loops, timeout, explicit failure outcome. Checkpoints are in-memory (`MemorySaver`); durable PostgreSQL saver still PENDING. `SpringAiAgentLlm` never run against a real model |
| Evidence verification (Module 10), report generation (Module 11) | Implemented, tested via graph tests | Deterministic verifier; report is data only, REST exposure in Phase 5. Engineer-review decision endpoint pending |
| REST API, security, rate limiting, OpenAPI | Implemented; components unit-tested (14 tests) | Async analyses, report fetch, human review gate, knowledge ingest/validate, JWT+RBAC, per-instance rate limit, correlation ids, problem+json. Security filter chain and controllers never exercised in a running context |
| LLM/vector/agent metrics, tracing, JSON logging config | Partial (ingestion + ES + embedding metrics only) | |
| Dockerfile, Compose, Kubernetes, Helm, Grafana | Pending | |
| End-to-end scenarios | Pending | |

## Next steps

1. Run `mvn test` where Docker is available to execute the pgvector integration tests and fix any SQL issues.
2. Start the application against PostgreSQL + Ollama to validate wiring (`spring.ai.model.*`, `_id` sort key).
3. Phase 6: durable agent checkpoints, context-startup and controller/security integration tests, e2e scenarios, LLM/vector/agent metrics, JSON logging, tracing. Phase 7: Docker/K8s/Helm.

See `docs/ASSUMPTIONS.md` for design decisions and `docs/RAG-DESIGN.md` for the retrieval design.
