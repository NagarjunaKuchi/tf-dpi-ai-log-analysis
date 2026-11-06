# Dependency versions — all UNVERIFIED

Maven Central was unreachable when `pom.xml` was written, so these were chosen from prior knowledge and
**must be confirmed** (resolved, API-checked and compiled) before relying on them.

| Dependency | Version in pom | Check |
|------------|----------------|-------|
| spring-boot-starter-parent | 4.0.1 | Exact patch exists. Spring AI 2.0.x supports Boot 4.0.x/4.1.x |
| spring-ai-bom | 2.0.2 | Chosen by project owner. Confirm it resolves, and confirm starter names (`spring-ai-starter-model-ollama`, `-openai`, `-vector-store-pgvector`): the docs page for these was not checked |
| langgraph4j-core | 1.6.0 | Exists; `StateGraph`, conditional edges, checkpoint saver API and Java 21 support |
| elasticsearch-java | 8.18.0 | Major version matches the existing cluster |
| springdoc-openapi-starter-webmvc-ui | 2.8.9 | Compatible with Boot 3.5 |
| resilience4j-spring-boot3 | 2.3.0 | Exists |
| logstash-logback-encoder | 8.1 | Exists |
| bucket4j_jdk17-core | 8.10.1 | Exists; artifact id |

Also confirm: PostgreSQL 16+ with pgvector 0.7+ (HNSW), and that `vector(1024)` matches the deployed embedding model.

## Consequences of Spring AI 2.0.x (Spring Boot 4)

The brief says "Spring Boot 3.x"; Spring AI 2.0.x cannot run on Boot 3.x, so the owner's choice of 2.0.2 moves the
project to Boot 4.0.x. Recorded in `docs/ASSUMPTIONS.md`. Items to re-check at first build:

- **Jackson 3.** Third-party sources say Spring AI 2 / Boot 4 move to Jackson 3. `StructuredLogMasker` imports
  `com.fasterxml.jackson.*` (Jackson 2). Migrate it to the Jackson 3 packages if Jackson 2 is not on the classpath.
- **Boot 4 modular starters.** Starter and auto-configuration artifacts were split up in Boot 4; the starters in
  `pom.xml` (Flyway, Actuator, OAuth2 resource server, test/Testcontainers) may need renamed or additional modules.
- **springdoc-openapi, resilience4j, bucket4j, logstash-logback-encoder, langgraph4j:** versions in the pom were picked
  for Boot 3; confirm each supports Boot 4 / Spring Framework 7.
- **Spring AI 2 changes reported by third parties (verify in the official upgrade notes):** default temperature
  removed (set it explicitly; the yml already does), immutable option builders, `FunctionCallback` replaced by
  `ToolCallback`.
