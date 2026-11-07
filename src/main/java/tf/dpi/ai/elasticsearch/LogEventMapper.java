package tf.dpi.ai.elasticsearch;

import com.fasterxml.jackson.databind.JsonNode;
import tf.dpi.ai.model.LogEvent;

/**
 * Turns one Elasticsearch document into a {@link LogEvent}. This is the extension point for onboarding another application:
 * most applications only need configuration ({@code dpi.elasticsearch.fields} and {@code dpi.elasticsearch.mapping}) and use
 * {@link ElasticsearchLogMapper}; an application whose documents need real logic (JSON inside the message, split stack traces,
 * unusual timestamps) provides its own single {@code LogEventMapper} bean, which replaces the default.
 *
 * <p>Implementations must be thread-safe and side-effect free, must carry over only the fields the platform needs, and must
 * report unusable documents with {@link tf.dpi.ai.exception.CorruptLogRecordException} using a reason that contains no log
 * content (the reason is logged and counted).
 */
public interface LogEventMapper {

    LogEvent map(String documentId, String index, JsonNode source);
}
