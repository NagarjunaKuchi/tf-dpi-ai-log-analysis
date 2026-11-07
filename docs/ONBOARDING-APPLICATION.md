# Onboarding another application

One deployment serves **one application** (its own Elasticsearch, its own index pattern, its own runbooks). To point a deployment at a
different application you change configuration; only unusual log formats need code. Nothing about the other application's cluster is
shared with this deployment.

## 1. Connection (read-only)
`DPI_ES_URIS`, `DPI_ES_INDEX_PATTERN`, and either `DPI_ES_API_KEY` or `DPI_ES_USER`/`DPI_ES_PASSWORD`, `DPI_ES_CA_PATH` for a private CA.
Use an account with only `read` and `view_index_metadata` on that application's indices.

## 2. Field names (`dpi.elasticsearch.fields.*`)
Dot paths into `_source`. Both a literal dotted key (`"log.level": "ERROR"`) and a nested object (`{"log": {"level": "ERROR"}}`) are read.
Keys: `timestamp` (default `@timestamp`), `service`, `host`, `level`, `message`, `trace-id`, `error-code`, `exception-type`, `stack-trace`
(default `stack_trace`). A missing optional field becomes empty; a missing or unreadable timestamp rejects that record (counted, not stored).

## 3. Value interpretation (`dpi.elasticsearch.mapping.*`)
| Key | Default | Use |
|---|---|---|
| `timestamp-formats` | `[iso, epoch_millis]` | Tried in order. Also `epoch_seconds` or a `java.time.format.DateTimeFormatter` pattern, e.g. `dd/MMM/yyyy:HH:mm:ss Z`. Numeric values are milliseconds unless only `epoch_seconds` is listed. |
| `timestamp-zone` | `UTC` | Zone for patterns that carry no offset. A pattern with an offset uses that offset. |
| `level-aliases` | none | Extra spellings, e.g. `E: ERROR`, `"3": ERROR`. Built-in: TRACE/FINEST, DEBUG/FINE, WARN/WARNING, ERROR/SEVERE, FATAL/CRITICAL/EMERGENCY/ALERT. |
| `exception-pattern` | built-in Java pattern | Regex with the exception type in group 1, applied to the first 1000 characters of the stack trace only when the exception-type field is empty. |

Invalid values stop the service at startup (they never fall back silently). Environment variables work through relaxed binding, e.g.
`DPI_ELASTICSEARCH_FIELDS_SERVICE=kubernetes.container.name`, `DPI_ELASTICSEARCH_MAPPING_TIMESTAMPFORMATS_0=iso`,
`DPI_ELASTICSEARCH_MAPPING_LEVELALIASES_E=ERROR` (env names lose case; level-alias keys are upper-cased by the mapper). In Helm use
`config.extraEnv`. For longer lists mount a YAML and set `SPRING_CONFIG_ADDITIONAL_LOCATION`.

> A level the mapper does not recognise becomes **INFO**. With the default `ingestion.min-level: WARN` such records are not ingested and
> nothing is reported, so check a sample of real level values and add aliases before trusting an onboarding.

## 4. When configuration is not enough: a custom mapper
Provide **one** Spring bean implementing `tf.dpi.ai.elasticsearch.LogEventMapper`; it replaces the configurable default. Two such beans stop
startup. The contract: thread-safe, side-effect free, carry over only the needed fields, and throw `CorruptLogRecordException` with a reason
that contains no log content.

```java
@Component
class PaymentsLogMapper implements LogEventMapper {
    @Override public LogEvent map(String documentId, String index, JsonNode source) { /* build the LogEvent */ }
}
```
(Illustrative; no such class ships. Add it to the application's own build/image, plus a test with recorded sample documents.)

## 5. Other parts that are application-specific
* **Runbooks** are ingested per deployment (`POST /api/v1/knowledge`); use this application's runbooks.
* **Scenario signatures** in the evidence verifier (DB_CONNECTION_REFUSED, OUT_OF_MEMORY, ACTIVEMQ_ISSUE) are DPI-specific and are **not** configurable yet; for another application they simply never trigger.
* **Masking**: review the rules against that application's logs (`dpi.masking.*`); masking is fail-closed but only knows the patterns it has.
* `ingestion.min-level`, `lookback`, `retention` are per deployment.

## 6. Checklist before relying on it
1. Fetch a few real documents and map them in a test (copy `ConfigurableMappingTest`).
2. Check the distribution of real level values and of timestamp shapes.
3. Run with ingestion enabled against a non-production cluster and read the dead-letter counter `dpi_ingestion_dead_lettered_total` (`/actuator/prometheus` on the management port). Records the mapper rejects are stored as dead letters at stage `VALIDATE` with a content-free reason.
4. Ask a question whose answer you know and read the cited evidence.

Not covered: extra application attributes are not carried into chunks (the platform embeds message, exception and stack trace only).
