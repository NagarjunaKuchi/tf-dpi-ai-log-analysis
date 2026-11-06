package tf.dpi.ai.ingestion;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import tf.dpi.ai.model.LogLevel;

/**
 * @param lookback  how far back to start when no checkpoint exists yet (first run)
 * @param minLevel  only events at or above this level are anchors; lower levels enter only as surrounding context
 * @param retention vectors whose time range ended before now - retention are purged by the retention job
 */
@ConfigurationProperties("dpi.ingestion")
public record IngestionProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("logstash") String streamName,
        @DefaultValue("500") int batchSize,
        @DefaultValue("20") int maxBatchesPerRun,
        @DefaultValue("3") int maxBatchAttempts,
        @DefaultValue("2s") Duration retryBackoff,
        @DefaultValue("5m") Duration interval,
        @DefaultValue("24h") Duration lookback,
        @DefaultValue("WARN") LogLevel minLevel,
        @DefaultValue("30d") Duration retention,
        @DefaultValue("60s") Duration shutdownGrace) {

    public IngestionProperties {
        if (batchSize < 1 || maxBatchesPerRun < 1 || maxBatchAttempts < 1) {
            throw new IllegalArgumentException("ingestion batch-size, max-batches-per-run, max-batch-attempts must be >= 1");
        }
    }
}
