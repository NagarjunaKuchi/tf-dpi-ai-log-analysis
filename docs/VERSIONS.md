# Dependency versions

Verified against Maven Central on 2026-11-06 (version exists; project compiles and unit tests pass).
"API unverified" means the library compiles in the build but its API has not yet been used by project code.

| Dependency | Version | Status |
|------------|---------|--------|
| spring-boot-starter-parent | 4.0.9 | Verified. Latest 4.0.x; Spring AI 2.0.x supports Boot 4.0.x/4.1.x |
| spring-ai-bom | 2.0.2 | Verified. Starter names resolved in the build |
| langgraph4j-core | 1.9.8 | Exists; resolved. API unverified |
| elasticsearch-java | 8.19.24 | Exists. **Must match the existing cluster's major version, which is unknown.** 9.5.x also exists |
| springdoc-openapi-starter-webmvc-ui | 3.2.0 | Exists; Boot 4 compatibility unverified at runtime |
| resilience4j-spring-boot4 | 2.4.0 | Exists (the `-boot3` artifact was replaced) |
| logstash-logback-encoder | 9.0 | Exists |
| bucket4j_jdk17-core | 8.22.0 | Exists |
| testcontainers (junit-jupiter, postgresql, elasticsearch) | managed by Boot BOM | Artifact ids renamed to `testcontainers-*` (Testcontainers 2.x) |

Still to confirm: PostgreSQL 16+ with pgvector 0.7+ (HNSW), and that `vector(1024)` matches the deployed embedding model.

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
