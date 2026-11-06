# DPI Agentic RAG Log Analysis (`tf.dpi.ai`)

Agentic RAG incident analysis on top of the existing Logstash → Elasticsearch → Kibana pipeline.
Architecture source of truth: *AI-POC-DPITeam_Enhanced.pptx* (Spring Boot + Spring AI, PostgreSQL + pgvector,
LangGraph4j, self-hosted Ollama/vLLM, engineer validation as the final gate).

## Status: foundation only — NOT production ready

Build verified: `mvn test-compile` succeeds and 33 unit tests pass (Spring Boot 4.0.9, Spring AI 2.0.2, Java 21).
Everything marked Pending has no code yet.

| Component | State | Verification |
|-----------|-------|--------------|
| Maven `pom.xml` | Written | Resolves and compiles; versions verified to exist (`docs/VERSIONS.md`). LangGraph4j, Spring AI and Elasticsearch APIs not yet exercised |
| Domain model, masking (12 categories, fail-closed), JSON masker, chunking | Implemented | 33 JUnit tests pass (`MaskingEngineTest` 24, `LogChunkerTest` 9) |
| Flyway `V1` schema (pgvector, HNSW, checkpoints, dead letter, analysis state) | Written | **Not applied** to a real PostgreSQL yet |
| `application.yml` | Skeleton | Not loaded (no Spring context test yet) |
| Elasticsearch integration (Module 1) | Pending | |
| Incremental ingestion pipeline (Module 2) | Pending | |
| Embedding service (Module 5) | Pending | |
| pgvector repositories / retrieval (Module 6) | Pending | |
| Runbook ingestion (Module 7) | Pending | |
| LangGraph4j agent, nodes, tools (Modules 8-9) | Pending | |
| Evidence verification (Module 10) | Pending | |
| Report generation (Module 11) | Pending | |
| REST API, security, rate limiting, OpenAPI | Pending | |
| Observability, Dockerfile, Compose, K8s, Helm | Pending | |
| End-to-end scenarios, Testcontainers tests | Pending | |

## Next step to unblock

Run `mvn -q -DskipTests compile` somewhere with Maven Central (or an internal mirror) access, then confirm the
versions in `docs/VERSIONS.md`. Phases 2–7 of the brief continue from there, compiling and testing at each phase.

See `docs/ASSUMPTIONS.md` for design decisions.
