package tf.dpi.ai.elasticsearch;

import java.time.Instant;
import java.util.List;
import tf.dpi.ai.model.LogEvent;

/** Read-only port onto the log store. There is deliberately no write, update or delete operation. */
public interface LogSource {

    /** Reads one page ordered by timestamp then tiebreaker, starting after {@code request.searchAfter()}. */
    LogPage readPage(ReadRequest request);

    /**
     * Surrounding context for an anchor event: same trace if it has one, otherwise same service and host, within
     * the configured window around {@code anchorTime}. Capped; excludes nothing so callers dedupe by id.
     */
    List<LogEvent> fetchContext(String service, String host, String traceId, Instant anchorTime);
}
