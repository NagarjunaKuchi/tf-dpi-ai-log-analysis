# DPI Agentic RAG Log Analysis (`tf.dpi.ai`)

Agentic RAG incident analysis on top of the existing Logstash → Elasticsearch → Kibana pipeline.
Architecture source of truth: *AI-POC-DPITeam_Enhanced.pptx* (Spring Boot + Spring AI, PostgreSQL + pgvector,
LangGraph4j, self-hosted Ollama/vLLM, engineer validation as the final gate).

## Status: foundation only — NOT production ready

This repository was started in an environment that **could not reach Maven Central**, so the full Maven build has
never run. Nothing below is claimed as built or tested beyond what the "Verification" column says.

| Component | State | Verification |
|-----------|-------|--------------|
| Maven `pom.xml` | Written | **Not resolved.** All versions unverified (`docs/VERSIONS.md`) |
| Domain model (`LogEvent`, `LogChunk`, `LogLevel`) | Implemented | Compiled with `javac`; exercised by scratch harness |
| Masking engine (fail-closed, 12 categories) | Implemented | Compiled; 54 behavior checks passed in a scratch harness (not JUnit) |
| Structured JSON masker | Implemented | **Not compiled** (needs Jackson) |
| Log grouping and chunking | Implemented | Compiled; scratch harness only |
| JUnit tests (masking, chunking) | Written | **Never run** (JUnit/AssertJ not resolvable here) |
| Flyway `V1` schema (pgvector, HNSW, checkpoints, dead letter, analysis state) | Written | **Not applied** to a real PostgreSQL |
| `application.yml` | Skeleton | Not loaded |
| Elasticsearch integration (Module 1) | Pending | |
| Incremental ingestion pipeline (Module 2) | Pending | |
| Embedding service (Module 5) | Pending | |
| pgvector repositories / retrieval (Module 6) | Pending | |
| Runbook ingestion (Module 7) | Pending | |
| LangGraph4j agent, nodes, tools (Modules 8–9) | Pending | |
| Evidence verification (Module 10) | Pending | |
| Report generation (Module 11) | Pending | |
| REST API, security, rate limiting, OpenAPI | Pending | |
| Observability, Dockerfile, Compose, K8s, Helm | Pending | |
| End-to-end scenarios, Testcontainers tests | Pending | |

## Next step to unblock

Run `mvn -q -DskipTests compile` somewhere with Maven Central (or an internal mirror) access, then confirm the
versions in `docs/VERSIONS.md`. Phases 2–7 of the brief continue from there, compiling and testing at each phase.

See `docs/ASSUMPTIONS.md` for design decisions.
