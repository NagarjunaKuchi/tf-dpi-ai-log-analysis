# Operations guide

Status: written from the code and from boot tests of the packaged jar against PostgreSQL 16 + pgvector 0.6.0. The container image, the
Compose stack and the Helm chart in a real cluster were **not** run (no Docker daemon / cluster in the authoring environment). The
chart was linted, rendered in four configurations and schema-validated with kubeconform (Kubernetes 1.30 schemas).

## Prerequisites you must provide
| Need | Notes |
|---|---|
| PostgreSQL 16+ with the `vector` extension | The Flyway user must be able to `CREATE EXTENSION vector` (or have a DBA create it first). Tested with pgvector 0.6.0 only. |
| Elasticsearch 8.19.x (existing ELK, unchanged) | Create a **read-only** account (index privileges `read`, `view_index_metadata` on the log indices). The service never writes. |
| JWT issuer | `DPI_JWT_ISSUER_URI`; tokens need a `roles` string-array claim: ANALYST, REVIEWER, KNOWLEDGE_ADMIN, OPERATOR. |
| LLM + embedding server | Ollama (default) or any OpenAI-compatible server such as vLLM. Models are never pulled by the app. |
| Secret | Kubernetes Secret with `DPI_DB_PASSWORD` (+ optional `DPI_ES_PASSWORD`, `DPI_ES_API_KEY`, `SPRING_AI_OPENAI_API_KEY`). |

## Deploy with Helm
```bash
helm upgrade --install dpi deploy/helm/dpi-log-analysis -n dpi --create-namespace \
  --set image.repository=<your registry>/dpi-log-analysis --set image.tag=<tag> \
  --set config.dbUrl=jdbc:postgresql://<pg>:5432/dpi --set config.jwtIssuerUri=<issuer> \
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
| `FATAL: invalid value for parameter "TimeZone"` | JVM zone id unknown to PostgreSQL; the image sets `-Duser.timezone=UTC`, keep it. |
| Startup error mentioning OpenAI credential | `llm.provider=openai` without `SPRING_AI_OPENAI_API_KEY`. |
| Analyses end `FAILED` quickly | LLM/embedding server unreachable or returning invalid structured output; see `dpi.llm.call{result="error"}` and logs (JSON, search by analysis id). |
| Analyses end `INSUFFICIENT_EVIDENCE` | Nothing relevant retrieved, or the verifier rejected every claim. Check ingestion lag and the embedding model/version filter. |
| No results after changing embedding model | Retrieval filters on model name+version; see `docs/EMBEDDING-MIGRATION.md`. |
