package tf.dpi.ai.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/** Ingestion metrics exposed to Prometheus via Micrometer. Names use the {@code dpi.ingestion.*} prefix. */
public class IngestionMetrics {

    private final Counter logsIngested;
    private final Counter logsMasked;
    private final Counter chunksGenerated;
    private final Counter deadLettered;
    private final Counter contextSkipped;
    private final Counter retries;
    private final Counter runsFailed;
    private final Timer runDuration;
    private final AtomicReference<Instant> lastCheckpoint = new AtomicReference<>();

    public IngestionMetrics(MeterRegistry registry, Clock clock) {
        logsIngested = Counter.builder("dpi.ingestion.logs.ingested").register(registry);
        logsMasked = Counter.builder("dpi.ingestion.logs.masked").register(registry);
        chunksGenerated = Counter.builder("dpi.ingestion.chunks.generated").register(registry);
        deadLettered = Counter.builder("dpi.ingestion.dead.lettered").register(registry);
        contextSkipped = Counter.builder("dpi.ingestion.context.skipped").register(registry);
        retries = Counter.builder("dpi.ingestion.retries").register(registry);
        runsFailed = Counter.builder("dpi.ingestion.runs.failed").register(registry);
        runDuration = Timer.builder("dpi.ingestion.run.duration").register(registry);
        Gauge.builder("dpi.ingestion.lag.seconds", () -> {
            Instant cp = lastCheckpoint.get();
            return cp == null ? Double.NaN : Math.max(0, clock.instant().getEpochSecond() - cp.getEpochSecond());
        }).description("Seconds between now and the last persisted log timestamp").register(registry);
    }

    public void ingested(long n) { logsIngested.increment(n); }
    public void masked(long n) { logsMasked.increment(n); }
    public void chunks(long n) { chunksGenerated.increment(n); }
    public void deadLettered(long n) { deadLettered.increment(n); }
    public void contextSkipped(long n) { contextSkipped.increment(n); }
    public void retried() { retries.increment(); }
    public void runFailed() { runsFailed.increment(); }
    public void checkpoint(Instant ts) { lastCheckpoint.set(ts); }
    public Timer runTimer() { return runDuration; }
}
