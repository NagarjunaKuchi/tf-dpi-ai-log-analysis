package tf.dpi.ai.elasticsearch;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Connection and field-mapping settings for the existing Elasticsearch cluster (accessed read-only).
 * Credentials come from the environment; at most one of {@code apiKey} or {@code username/password} is used.
 */
@ConfigurationProperties("dpi.elasticsearch")
public record ElasticsearchProperties(
        List<String> uris,
        @DefaultValue("logstash-*") String indexPattern,
        String username,
        String password,
        String apiKey,
        String caCertificatePath,
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("30s") Duration socketTimeout,
        @DefaultValue("500") int pageSize,
        @DefaultValue("3") int maxRetries,
        @DefaultValue("500ms") Duration initialBackoff,
        @DefaultValue("30s") Duration maxBackoff,
        @DefaultValue("_id") String tiebreakerField,
        @DefaultValue("30s") Duration contextBefore,
        @DefaultValue("30s") Duration contextAfter,
        @DefaultValue("50") int maxContextEvents,
        @DefaultValue Fields fields,
        @DefaultValue Mapping mapping) {

    public ElasticsearchProperties {
        if (uris == null || uris.isEmpty()) {
            throw new IllegalArgumentException("dpi.elasticsearch.uris must contain at least one URI");
        }
        uris = List.copyOf(uris);
        if (pageSize < 1 || pageSize > 10_000) {
            throw new IllegalArgumentException("dpi.elasticsearch.page-size must be within 1..10000");
        }
        if (maxRetries < 0 || maxRetries > 10) {
            throw new IllegalArgumentException("dpi.elasticsearch.max-retries must be within 0..10");
        }
    }

    /**
     * How raw values are interpreted. All values are validated at startup; invalid configuration stops the service.
     *
     * @param timestampFormats tried in order. {@code iso} (ISO-8601 instant/offset), {@code epoch_millis}, {@code epoch_seconds},
     *                         or a {@link java.time.format.DateTimeFormatter} pattern (e.g. {@code dd/MMM/yyyy:HH:mm:ss Z}).
     *                         Numeric values are read as epoch millis unless only {@code epoch_seconds} is listed.
     * @param timestampZone    zone applied to pattern formats that carry no offset
     * @param levelAliases     extra level spellings, upper-cased source value to LogLevel name (e.g. {@code E: ERROR, "3": ERROR})
     * @param exceptionPattern regex with the exception type in group 1, applied to the first line of the stack trace when the
     *                         exception-type field is empty; empty = the built-in Java pattern. Operator-supplied: avoid nested quantifiers.
     */
    public record Mapping(
            @DefaultValue({"iso", "epoch_millis"}) List<String> timestampFormats,
            @DefaultValue("UTC") String timestampZone,
            @DefaultValue java.util.Map<String, String> levelAliases,
            @DefaultValue("") String exceptionPattern) {

        public Mapping {
            if (timestampFormats == null || timestampFormats.isEmpty()) {
                throw new IllegalArgumentException("dpi.elasticsearch.mapping.timestamp-formats must not be empty");
            }
            timestampFormats = List.copyOf(timestampFormats);
            levelAliases = levelAliases == null ? java.util.Map.of() : java.util.Map.copyOf(levelAliases);
            exceptionPattern = exceptionPattern == null ? "" : exceptionPattern;
        }

        public static Mapping defaults() {
            return new Mapping(List.of("iso", "epoch_millis"), "UTC", java.util.Map.of(), "");
        }
    }

    /** Source field paths (dot notation). Defaults assume a flat Logstash layout; override per deployment. */
    public record Fields(
            @DefaultValue("@timestamp") String timestamp,
            @DefaultValue("service") String service,
            @DefaultValue("host") String host,
            @DefaultValue("level") String level,
            @DefaultValue("message") String message,
            @DefaultValue("traceId") String traceId,
            @DefaultValue("errorCode") String errorCode,
            @DefaultValue("exceptionType") String exceptionType,
            @DefaultValue("stack_trace") String stackTrace) {
    }
}
