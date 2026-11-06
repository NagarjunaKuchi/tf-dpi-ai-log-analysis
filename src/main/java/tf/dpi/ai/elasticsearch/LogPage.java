package tf.dpi.ai.elasticsearch;

import java.util.List;
import tf.dpi.ai.model.LogEvent;

/**
 * Result of one page read.
 *
 * @param events       valid events in source sort order
 * @param rejected     documents that could not be mapped (to be dead-lettered, never silently dropped)
 * @param lastSortKey  sort values [timestampMillis, tiebreaker] of the LAST document returned (valid or rejected);
 *                     empty when the page was empty
 * @param exhausted    true when fewer documents than requested came back
 */
public record LogPage(List<LogEvent> events, List<RejectedRecord> rejected, List<String> lastSortKey, boolean exhausted) {

    public LogPage {
        events = List.copyOf(events);
        rejected = List.copyOf(rejected);
        lastSortKey = List.copyOf(lastSortKey);
    }

    public boolean isEmpty() {
        return events.isEmpty() && rejected.isEmpty();
    }

    /** A document that failed validation/mapping; {@code reason} contains no log content. */
    public record RejectedRecord(String documentId, String index, String reason) {
    }
}
