# Operations guide

Status: written from the code and from boot tests of the packaged jar against PostgreSQL 16 + pgvector 0.6.0. The container image, the
Compose stack and the Helm chart in a real cluster were **not** run (no Docker daemon / cluster in the authoring environment). The
chart was linted, rendered in four configurations and schema-validated with kubeconform (Kubernetes 1.30 schemas).

## Prerequisites you must provide
| Need | Notes |
|---|---|
| PostgreSQL 16+ with the `vector` extension | The Flyway user must be able to `CREATE EXTENSION vector` (or have a DBA create it first). Tested with pgvector 0.6.0 only. |
| Elasticsearch 8.19.x (existing ELK, unchanged) | Create a **read-only** account (index privileges `read`, `view_index_metadata` on the log indices). The service never writes. |
| OIDC identity provider | Keycloak is the supported/tested provider (see [Identity: Keycloak](#identity-keycloak)). Any OIDC provider issuing RS256 JWTs with the roles ANALYST, REVIEWER, KNOWLEDGE_ADMIN, OPERATOR works. |
| LLM + embedding server | Ollama (default) or any OpenAI-compatible server such as vLLM. Models are never pulled by the app. |
| Secret | Kubernetes Secret with `DPI_DB_PASSWORD` (+ optional `DPI_ES_PASSWORD`, `DPI_ES_API_KEY`, `SPRING_AI_OPENAI_API_KEY`). |

## Identity: Keycloak
The API is an OAuth2 resource server. It validates the token **signature (RS256 only)**, `iss` (must equal `DPI_JWT_ISSUER_URI`), expiry, `sub`, and `aud` (must contain
`DPI_JWT_AUDIENCE`, default `dpi-log-analysis`). Authorities come from the top-level `roles` claim, Keycloak's `realm_access.roles`, and `resource_access.<audience>.roles`;
only upper-case names (`ANALYST`, `REVIEWER`, `KNOWLEDGE_ADMIN`, `OPERATOR`) are used, Keycloak's own roles (`offline_access`, `uma_authorization`, ...) are ignored.

| Setting | Meaning |
|---|---|
| `DPI_JWT_ISSUER_URI` | The exact string in the token's `iss` (Keycloak: `https://<host>/realms/<realm>`). Required; the service refuses to start without it. |
| `DPI_JWK_SET_URI` | Optional. Key endpoint the **service** calls. Set it when the issuer URL is not reachable from the container/pod (typical: tokens say `localhost` or the public host, the service reaches Keycloak by an internal name). Empty = fetch the issuer's discovery document. |
| `DPI_JWT_AUDIENCE` | Required `aud`; empty disables the check (not recommended). Keycloak only adds it through an audience mapper (the imported realm has one). |

Keys are fetched lazily, so the service starts even if Keycloak is not up yet and succeeds on a later request (verified locally, see below).

Dev realm: `deploy/keycloak/dpi-realm.json` defines realm `dpi`, the four roles and a public client `dpi-log-analysis` (authorization-code + PKCE S256, no password grant, no secrets).
It contains **no users**: create them in the admin console and assign realm roles. Run it with `docker compose --profile keycloak up` or Terraform (`deploy_keycloak=true`, default).
Both use `start-dev` (embedded H2, HTTP): **development only**. Production Keycloak needs its own database, TLS, a fixed hostname, backups, and a decision on who administers it
(admin credentials and user/role lifecycle are outside this service).

Smoke test with real tokens (creates two temporary users with random passwords, temporarily enables the password grant, removes both afterwards):
```bash
KC_ADMIN_PASSWORD=... KC_URL=http://localhost:8180 APP_URL=http://localhost:8080 deploy/keycloak/smoke-test.sh
```

## Deploy with Helm
```bash
helm upgrade --install dpi deploy/helm/dpi-log-analysis -n dpi --create-namespace \
  --set image.repository=<your registry>/dpi-log-analysis --set image.tag=<tag> \
  --set config.dbUrl=jdbc:postgresql://<pg>:5432/dpi --set config.jwtIssuerUri=<issuer> [--set config.jwkSetUri=<internal JWKS url>] \
  --set config.esUris=https://<es>:9200 --set existingSecret=dpi-secrets --set llm.baseUrl=http://<ollama>:11434
```
Required values are enforced at render time. Ports: 8080 API, 8081 management (health + Prometheus). The Ingress only routes `/api`.
Replicas are safe: ingestion is single-writer through a PostgreSQL advisory lock; analyses are independent.

### Models
- Ollama: `ollama pull llama3.1:8b` and `ollama pull bge-m3` on the Ollama host (the in-cluster Ollama in the chart does not pull for you).
- vLLM / OpenAI-compatible: `--set llm.provider=openai --set llm.baseUrl=http://vllm:8000`. **A non-empty `SPRING_AI_OPENAI_API_KEY` is mandatory
  even if the server ignores it** (the app fails to start without one; a dummy value is fine). The embedding model must produce
  **1024-dimension** vectors or the pgvector column and the service must be changed together.
- Chat model quality matters: it must return valid JSON for the structured outputs. Malformed output ends an analysis as `FAILED`, never as a guess.

## Health and probes
`/actuator/health/liveness` and `/readiness` on 8081 (used by the chart). Aggregate `/actuator/health` also includes Elasticsearch and reports
DOWN while it is unreachable; readiness deliberately does not depend on it so stored data stays queryable.

## Metrics and dashboard
Scrape `:8081/actuator/prometheus` (unauthenticated on that port only; the NetworkPolicy limits it to the monitoring namespace).
Import `deploy/grafana/dpi-dashboard.json`. Timers are summaries (no buckets), so the dashboard shows averages and maxima.
Suggested alerts (tune thresholds):
- `rate(dpi_analysis_completed_total{outcome=~"FAILED|CRASHED"}[15m]) > 0` for 15m
- `dpi_ingestion_lag_seconds > 900` for 10m
- `increase(dpi_ingestion_dead_lettered_total[1h]) > 0`
- `rate(dpi_llm_call_seconds_count{result="error"}[5m]) > 0` for 10m
- `sum(rate(dpi_evidence_claims_total{result="rejected"}[1h])) / sum(rate(dpi_evidence_claims_total[1h])) > 0.5`

## Routine tasks
- **Human review queue:** `incident_report.review_status = 'PENDING_REVIEW'`. Decisions are recorded once through the API and audited in `audit_log`.
- **Knowledge:** ingest through `POST /api/v1/knowledge`; versions are `UNVERIFIED` until a REVIEWER validates them.
- **Retention:** `dpi.ingestion.retention` purges old log vectors; `audit_log`, `incident_report` and `analysis_request` are never purged automatically (decide your policy).
- **Dead letters:** `ingestion_dead_letter` holds records that could not be processed (reason only, no content). They are not retried automatically.
- **Restart behaviour:** in-flight analyses are lost; stale ones become `FAILED/INTERRUPTED` after ~3 minutes with default settings. Users resubmit.

## Backup / recovery
Everything durable is in PostgreSQL: back it up with your standard tooling. Vectors are re-derivable from Elasticsearch only within ES retention;
knowledge vectors are re-derivable from the stored text (`knowledge_vectors.content`).

## Troubleshooting
| Symptom | Likely cause |
|---|---|
| Pod crash-loops, "jwt-issuer-uri must be configured" | `config.jwtIssuerUri` empty (fail-closed by design). |
| Every call returns 401 with a valid-looking Keycloak token | `iss` differs from `DPI_JWT_ISSUER_URI` (compare the decoded token; Keycloak uses its configured hostname), the `aud` lacks `DPI_JWT_AUDIENCE` (audience mapper missing), the token is expired, or the keys cannot be fetched (set `DPI_JWK_SET_URI` to an address the pod can reach). |
| 403 for a signed-in user | The user has no matching realm role (ANALYST/REVIEWER/KNOWLEDGE_ADMIN/OPERATOR) assigned. |
| `FATAL: invalid value for parameter "TimeZone"` | JVM zone id unknown to PostgreSQL; the image sets `-Duser.timezone=UTC`, keep it. |
| Startup error mentioning OpenAI credential | `llm.provider=openai` without `SPRING_AI_OPENAI_API_KEY`. |
| Analyses end `FAILED` quickly | LLM/embedding server unreachable or returning invalid structured output; see `dpi.llm.call{result="error"}` and logs (JSON, search by analysis id). |
| Analyses end `INSUFFICIENT_EVIDENCE` | Nothing relevant retrieved, or the verifier rejected every claim. Check ingestion lag and the embedding model/version filter. |
| No results after changing embedding model | Retrieval filters on model name+version; see `docs/EMBEDDING-MIGRATION.md`. |
