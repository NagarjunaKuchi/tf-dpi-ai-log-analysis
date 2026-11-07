# DPI Agentic RAG Log Analysis

Agentic RAG incident analysis on top of the existing Logstash → Elasticsearch → Kibana pipeline.
Architecture source of truth: *AI-POC-DPITeam_Enhanced.pptx* (Spring Boot + Spring AI, PostgreSQL + pgvector,
LangGraph4j, self-hosted Ollama/vLLM, engineer validation as the final gate).

## Status: all phases implemented — NOT production ready (see "Not verified" below)

`mvn test`: 110 tests pass against a real PostgreSQL 16 + pgvector 0.6.0 (12 skip when no database is configured). Stack: Spring Boot 4.0.9,
Spring AI 2.0.2, Java 21, LangGraph4j 1.9.8, Elasticsearch client 8.19.24.

| Component | State | Verification |
|-----------|-------|--------------|
| Build / dependencies | Done | Resolves and compiles (`docs/VERSIONS.md`) |
| Masking (12 categories, JSON-aware, fail-closed) + event sanitizer | Done | 24 unit tests |
| Log grouping and chunking | Done | 11 unit tests |
| Elasticsearch module (client+TLS+auth, query builder, mapper, read-only reader with bounded retry, health) | Done | 22 unit tests with a mocked client. **Not run against a real cluster** |
| Embedding service (Spring AI `EmbeddingModel`, batching, timeout, dimension check, duplicate guard) | Done | 8 unit tests with a mocked model. **Not run against Ollama/vLLM** |
| Incremental ingestion (checkpoint, dead letter, retry, atomic commit, graceful stop) | Done | 14 unit tests with fakes |
| JDBC store, Flyway schema, retention job, advisory-lock scheduler | Done | 6 integration tests pass on PostgreSQL 16 + pgvector 0.6.0 (Flyway, HNSW, atomic commit, retention) |
| Spring application context / wiring | Done | Full context starts (test + packaged jar boot, ~12 s) against real PostgreSQL; Ollama and OpenAI-compatible (vLLM) provider selection both start. Elasticsearch/LLM servers were unreachable stubs in those runs |
| Embedding model migration (re-embed on version change) | Documented manual procedure only | `docs/EMBEDDING-MIGRATION.md`; no automation |
| Runbook / incident knowledge ingestion (Module 7) | Implemented; integration-tested on PostgreSQL 16 + pgvector 0.6.0 | Versioning, masking, UNVERIFIED-by-default, atomic supersession, validation. HTTP endpoints in Phase 5 row |
| Vector retrieval service (Spring AI `VectorStore`, filters) | Implemented; integration-tested | `VectorStoreDocumentRetriever` + metadata filters pinned to embedding model/version. Service/level/time-overlap/embedding-model filters and similarity threshold verified against real pgvector with a fake embedding model (real model quality untested) |
| LangGraph4j agent, nodes, tools (Modules 8-9) | Implemented, 6 graph tests with a fake LLM | 9 nodes, bounded loops, timeout, explicit failure outcome. Run state is in-memory (`MemorySaver`) so runs are NOT resumable after a restart; the content-free step trail is persisted to `agent_checkpoint` and stale runs are failed as INTERRUPTED. Durable resumable checkpoints are NOT implemented. `SpringAiAgentLlm` never run against a real model |
| Evidence verification (Module 10), report generation (Module 11) | Implemented, tested via graph tests | Deterministic verifier; report is data only, REST exposure in Phase 5. Review decision endpoint is in the REST row |
| REST API, security, rate limiting, OpenAPI | Implemented; unit-tested and smoke-tested with real Keycloak tokens | Async analyses, report fetch, human review gate, knowledge ingest/validate, Keycloak/OIDC JWT + RBAC (RS256, issuer, audience), per-instance rate limit, correlation ids, problem+json. Real-token smoke test (`deploy/keycloak/smoke-test.sh`) passed locally against Keycloak 26.8.2 with a single app instance; production IdP setup untested |
| LLM/retrieval/analysis/evidence metrics, JSON logging | Implemented | Metric names confirmed from a live scrape of the jar; distributed tracing NOT implemented |
| CI pipeline (GitHub Actions), local Terraform IaC | Written; workflow actionlint-clean, test commands run locally; Terraform fmt+validate OK | Never ran on GitHub; image build/push, Trivy scan and `terraform apply` never executed. See `docs/CI-CD.md` |
| Dockerfile, Compose, Kubernetes (Helm), Grafana | Written; chart linted, rendered (4 configs) and kubeconform-validated; jar boot-tested | Dockerfile and Compose never built/run (no Docker daemon); chart never installed in a cluster; dashboard JSON never imported into Grafana |
| End-to-end scenarios | Implemented (6, scripted LLM) | Real graph/tools/pgvector; LLM and Elasticsearch are stubs |

## Not verified (must be done before any production claim)

1. Real Elasticsearch 8.19.24 with the actual Logstash field mapping (flat field names and the `_id` sort tiebreaker are assumptions).
2. A real Ollama / vLLM chat model (structured-output reliability) and the real BGE-M3 embedding model (retrieval quality, 1024 dimensions).
3. A production identity provider setup (Keycloak token validation is tested locally against Keycloak 26.8.2, see docs/OPERATIONS.md); the Docker image, Compose stack, Helm install, NetworkPolicy enforcement and Grafana import.
4. Production PostgreSQL / pgvector versions, load and capacity behaviour (no performance test exists), a dependency CVE scan.
5. Masking rules against real MOSIP/DPI log samples; durable resumable agent checkpoints and distributed tracing are not implemented.

See `docs/CI-CD.md`, `docs/OPERATIONS.md`, `docs/SECURITY-ASSESSMENT.md`, `docs/PERFORMANCE.md`, `docs/EMBEDDING-MIGRATION.md`,
`docs/ASSUMPTIONS.md` for design decisions and `docs/RAG-DESIGN.md` for the retrieval design.


## Running the tests

- `mvn test` runs unit tests; database tests are skipped without Docker or a database.
- Set `DPI_TEST_JDBC_URL` (plus `DPI_TEST_JDBC_USER`, `DPI_TEST_JDBC_PASSWORD`) to run all integration tests, including the full
  Spring context test, against a disposable PostgreSQL with the pgvector extension. **The `public` schema is dropped and
  re-migrated on every run.** Without that variable the tests use a Testcontainers pgvector container when Docker is available.
- Last verified run: 110 tests, 0 failures, 0 skipped, on PostgreSQL 16.15 with pgvector 0.6.0 (Ubuntu packages). Not yet run on
  the production PostgreSQL / pgvector versions, against real Elasticsearch 8.19.24, or against a real Ollama / vLLM model.

## Observability

Metrics (Micrometer, `/actuator/prometheus`): `dpi.llm.call{operation,result}` (timer), `dpi.retrieval.latency{collection}`,
`dpi.analysis.duration{outcome}`, `dpi.analysis.completed{outcome}`, `dpi.evidence.claims{result=verified|rejected}`, plus the
existing `dpi.ingestion.*`. Logs are JSON (profile `local` switches to text). `correlationId` is in the MDC for HTTP request
threads and the analysis worker; LangGraph4j node threads do not inherit it, so node logs carry the analysis id in the message.
Not implemented: distributed tracing (no tracing bridge/exporter configured) and Grafana dashboards (Phase 7).

## Defects found by integration testing (fixed)

1. Ollama + OpenAI starters together made the context fail to start (OpenAI audio models need an API key): unused model types are now disabled.
2. Spring AI's default model-call retry (10 attempts, 5x backoff) let one analysis run for minutes with the LLM down: capped at 2 attempts, max 5 s backoff.
3. A JVM timezone id unknown to PostgreSQL (e.g. `Asia/Calcutta`) makes the JDBC connection fail: run the service with `-Duser.timezone=UTC` (tests do this via the pom).
4. LangGraph4j node threads do not inherit the logging MDC: node log lines now include the analysis id explicitly.
