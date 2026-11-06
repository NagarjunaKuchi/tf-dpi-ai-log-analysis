package tf.dpi.ai.model;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * One log record read from Elasticsearch. Fields are either raw (as read) or sanitized (after masking);
 * the ingestion pipeline guarantees only sanitized events reach chunking, embedding and storage.
 *
 * @param id            Elasticsearch document identifier (stable, used for idempotency and evidence references)
 * @param sourceIndex   Elasticsearch index the document came from
 * @param exceptionType fully qualified exception class of the primary exception, if any
 * @param stackTrace    complete stack trace text including "Caused by" chain, or empty
 */
public record LogEvent(
        String id,
        String sourceIndex,
        Instant timestamp,
        String service,
        String host,
        LogLevel level,
        String traceId,
        String errorCode,
        String exceptionType,
        String message,
        String stackTrace,
        Map<String, String> attributes) {

    public LogEvent {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("LogEvent id must not be blank");
        }
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(level, "level");
        sourceIndex = sourceIndex == null ? "" : sourceIndex;
        service = isBlank(service) ? "unknown" : service;
        host = host == null ? "" : host;
        traceId = traceId == null ? "" : traceId;
        errorCode = errorCode == null ? "" : errorCode;
        exceptionType = exceptionType == null ? "" : exceptionType;
        message = message == null ? "" : message;
        stackTrace = stackTrace == null ? "" : stackTrace;
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** Returns a copy with free-text fields replaced (used to apply masking). */
    public LogEvent withText(String newMessage, String newStackTrace, Map<String, String> newAttributes) {
        return new LogEvent(id, sourceIndex, timestamp, service, host, level, traceId, errorCode,
                exceptionType, newMessage, newStackTrace, newAttributes);
    }
}
