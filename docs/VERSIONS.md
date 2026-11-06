# Dependency versions — all UNVERIFIED

Maven Central was unreachable when `pom.xml` was written, so these were chosen from prior knowledge and
**must be confirmed** (resolved, API-checked and compiled) before relying on them.

| Dependency | Version in pom | Check |
|------------|----------------|-------|
| spring-boot-starter-parent | 3.5.6 | Exists; compatible with chosen Spring AI |
| spring-ai-bom | 1.0.3 | Exists; supports Boot 3.5; starter artifact names (`spring-ai-starter-model-ollama`, `-openai`, `-vector-store-pgvector`) |
| langgraph4j-core | 1.6.0 | Exists; `StateGraph`, conditional edges, checkpoint saver API and Java 21 support |
| elasticsearch-java | 8.18.0 | Major version matches the existing cluster |
| springdoc-openapi-starter-webmvc-ui | 2.8.9 | Compatible with Boot 3.5 |
| resilience4j-spring-boot3 | 2.3.0 | Exists |
| logstash-logback-encoder | 8.1 | Exists |
| bucket4j_jdk17-core | 8.10.1 | Exists; artifact id |

Also confirm: PostgreSQL 16+ with pgvector 0.7+ (HNSW), and that `vector(1024)` matches the deployed embedding model.
