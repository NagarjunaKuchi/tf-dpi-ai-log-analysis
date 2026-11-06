package tf.dpi.ai.vectorstore;

import java.time.Instant;
import tf.dpi.ai.model.LogLevel;

/** Optional metadata filters for log-chunk search. Null / blank means "no constraint". */
public record SearchFilters(String service, LogLevel minLevel, Instant from, Instant to, String errorCode,
        String traceId, String host) {

    public static SearchFilters none() {
        return new SearchFilters(null, null, null, null, null, null, null);
    }

    public SearchFilters withWindow(Instant newFrom, Instant newTo) {
        return new SearchFilters(service, minLevel, newFrom, newTo, errorCode, traceId, host);
    }

    public SearchFilters withoutNarrowing() {
        return new SearchFilters(null, minLevel, from, to, null, null, null);
    }
}
