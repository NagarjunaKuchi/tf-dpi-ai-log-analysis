package tf.dpi.ai.chunking;

import java.time.Duration;

/**
 * Chunking configuration.
 *
 * @param maxChunkTokens       estimated token budget per chunk (heuristic: ~4 characters per token)
 * @param maxGap               a gap longer than this between events of one group starts a new group
 * @param maxSpan              a group never spans more than this duration
 * @param overlapEvents        trailing events of the previous chunk repeated as context when a group is split
 * @param maxFramesPerException stack frames kept per exception when a single event exceeds the budget
 */
public record ChunkingProperties(
        int maxChunkTokens,
        Duration maxGap,
        Duration maxSpan,
        int overlapEvents,
        int maxFramesPerException) {

    public static final String CHUNKER_VERSION = "1";

    public ChunkingProperties {
        if (maxChunkTokens < 64) {
            throw new IllegalArgumentException("maxChunkTokens must be at least 64");
        }
        if (maxGap == null || maxGap.isNegative() || maxGap.isZero()) {
            throw new IllegalArgumentException("maxGap must be positive");
        }
        if (maxSpan == null || maxSpan.compareTo(maxGap) < 0) {
            throw new IllegalArgumentException("maxSpan must be >= maxGap");
        }
        if (overlapEvents < 0 || maxFramesPerException < 1) {
            throw new IllegalArgumentException("invalid overlapEvents / maxFramesPerException");
        }
    }

    public static ChunkingProperties defaults() {
        return new ChunkingProperties(512, Duration.ofSeconds(60), Duration.ofMinutes(10), 1, 8);
    }
}
