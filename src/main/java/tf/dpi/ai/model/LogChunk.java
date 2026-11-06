package tf.dpi.ai.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A group of related, already-sanitized log events ready for embedding.
 *
 * @param chunkId   deterministic id derived from the source document ids, so re-ingestion is idempotent
 * @param sourceIds Elasticsearch document identifiers of every event in {@code content}
 * @param metadata  chunking metadata (strategy, token estimate, truncation flags, group key, ...)
 */
public record LogChunk(
        String chunkId,
        String service,
        LogLevel level,
        Instant fromTimestamp,
        Instant toTimestamp,
        String errorCode,
        String traceId,
        String host,
        String content,
        List<String> sourceIds,
        Map<String, String> metadata) {

    public LogChunk {
        sourceIds = List.copyOf(sourceIds);
        metadata = Map.copyOf(metadata);
    }
}
