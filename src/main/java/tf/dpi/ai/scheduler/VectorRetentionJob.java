package tf.dpi.ai.scheduler;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tf.dpi.ai.ingestion.IngestionProperties;
import tf.dpi.ai.ingestion.JdbcIngestionStore;

/** Rolling retention window for log-chunk vectors (runbooks and validated incidents are not purged here). */
@Component
public class VectorRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(VectorRetentionJob.class);

    private final JdbcIngestionStore store;
    private final IngestionProperties props;
    private final Clock clock;

    public VectorRetentionJob(JdbcIngestionStore store, IngestionProperties props, Clock clock) {
        this.store = store;
        this.props = props;
        this.clock = clock;
    }

    @Scheduled(cron = "${dpi.ingestion.retention-cron:0 30 3 * * *}")
    public void purge() {
        try {
            int removed = store.purgeOlderThan(clock.instant().minus(props.retention()));
            log.info("Retention purge removed {} log chunk vector(s)", removed);
        } catch (RuntimeException e) {
            log.error("Retention purge failed; will retry at the next scheduled time", e);
        }
    }
}
