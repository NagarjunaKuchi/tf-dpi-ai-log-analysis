package tf.dpi.ai.elasticsearch;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import tf.dpi.ai.model.LogLevel;

/**
 * One page read.
 *
 * @param afterTimestamp inclusive lower bound on the timestamp; null for "from the beginning"
 * @param searchAfter    sort values of the last record already persisted (exclusive), or empty
 * @param until          exclusive upper bound; null for "now"
 * @param services       optional service filter (empty = all)
 */
public record ReadRequest(
        Instant afterTimestamp,
        List<String> searchAfter,
        Instant until,
        LogLevel minLevel,
        Set<String> services,
        Set<String> hosts,
        String traceId,
        String errorCode,
        int size) {

    public ReadRequest {
        searchAfter = searchAfter == null ? List.of() : List.copyOf(searchAfter);
        services = services == null ? Set.of() : Set.copyOf(services);
        hosts = hosts == null ? Set.of() : Set.copyOf(hosts);
        if (size < 1) {
            throw new IllegalArgumentException("size must be positive");
        }
    }
}
