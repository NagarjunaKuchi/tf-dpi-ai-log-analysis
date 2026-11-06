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
        @DefaultValue Fields fields) {

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
