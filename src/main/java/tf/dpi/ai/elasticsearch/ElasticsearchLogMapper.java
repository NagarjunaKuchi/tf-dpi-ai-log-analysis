package tf.dpi.ai.elasticsearch;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tf.dpi.ai.exception.CorruptLogRecordException;
import tf.dpi.ai.model.LogEvent;
import tf.dpi.ai.model.LogLevel;

/**
 * Maps an Elasticsearch document to a {@link LogEvent}. Only the fields the platform needs are carried over
 * (data minimisation). Failures raise {@link CorruptLogRecordException} with a content-free reason.
 */
public class ElasticsearchLogMapper {

    private static final Pattern EXCEPTION_HEAD =
            Pattern.compile("^\\s*([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)+(?:Exception|Error|Throwable))\\b");

    private final ElasticsearchProperties.Fields fields;

    public ElasticsearchLogMapper(ElasticsearchProperties.Fields fields) {
        this.fields = fields;
    }

    public LogEvent map(String documentId, String index, JsonNode source) {
        if (documentId == null || documentId.isBlank()) {
            throw new CorruptLogRecordException("<missing>", "document has no id");
        }
        if (source == null || !source.isObject()) {
            throw new CorruptLogRecordException(documentId, "document has no _source object");
        }
        Instant timestamp = parseTimestamp(documentId, resolve(source, fields.timestamp()));
        String stackTrace = text(resolve(source, fields.stackTrace()));
        String exceptionType = text(resolve(source, fields.exceptionType()));
        if (exceptionType.isBlank()) {
            Matcher m = EXCEPTION_HEAD.matcher(stackTrace);
            if (m.find()) {
                exceptionType = m.group(1);
            }
        }
        return new LogEvent(
                documentId,
                index,
                timestamp,
                text(resolve(source, fields.service())),
                text(resolve(source, fields.host())),
                LogLevel.parse(text(resolve(source, fields.level()))),
                text(resolve(source, fields.traceId())),
                text(resolve(source, fields.errorCode())),
                exceptionType,
                text(resolve(source, fields.message())),
                stackTrace,
                java.util.Map.of());
    }

    private static Instant parseTimestamp(String documentId, JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            throw new CorruptLogRecordException(documentId, "timestamp missing");
        }
        try {
            if (node.isNumber()) {
                return Instant.ofEpochMilli(node.asLong());
            }
            return Instant.parse(node.asText().trim());
        } catch (DateTimeException e) {
            throw new CorruptLogRecordException(documentId, "timestamp unparseable");
        }
    }

    /** Accepts both flat keys ("log.level" stored literally) and nested objects. */
    static JsonNode resolve(JsonNode root, String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        JsonNode flat = root.get(path);
        if (flat != null) {
            return flat;
        }
        JsonNode current = root;
        for (String part : path.split("\\.")) {
            if (current == null || !current.isObject()) {
                return null;
            }
            current = current.get(part);
        }
        return current;
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "";
        }
        if (node.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode n : node) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(n.asText());
            }
            return sb.toString();
        }
        return node.isValueNode() ? node.asText() : node.toString();
    }
}
