package tf.dpi.ai.ingestion;

import java.time.Instant;
import java.util.List;

/** Position of the last record that is durably persisted (or durably dead-lettered). */
public record Checkpoint(Instant lastTimestamp, List<String> sortKey) {

    public Checkpoint {
        sortKey = List.copyOf(sortKey);
    }

    public static Checkpoint fromSortKey(List<String> sortKey) {
        return new Checkpoint(Instant.ofEpochMilli(Long.parseLong(sortKey.get(0))), sortKey);
    }
}
