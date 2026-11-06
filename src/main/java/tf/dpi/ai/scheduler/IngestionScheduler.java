package tf.dpi.ai.scheduler;

import jakarta.annotation.PreDestroy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tf.dpi.ai.exception.DependencyUnavailableException;
import tf.dpi.ai.ingestion.IngestionProperties;
import tf.dpi.ai.ingestion.IngestionResult;
import tf.dpi.ai.ingestion.LogIngestionService;
import tf.dpi.ai.observability.IngestionMetrics;

/**
 * Runs ingestion on a fixed delay. A PostgreSQL advisory lock, held on one dedicated connection for the duration
 * of the run, ensures only one replica ingests a given stream at a time, so API pods scale horizontally while
 * ingestion stays single-writer per stream.
 */
@Component
@ConditionalOnProperty(prefix = "dpi.ingestion", name = "enabled", havingValue = "true", matchIfMissing = true)
public class IngestionScheduler {

    private static final Logger log = LoggerFactory.getLogger(IngestionScheduler.class);

    private final LogIngestionService service;
    private final DataSource dataSource;
    private final IngestionProperties props;
    private final IngestionMetrics metrics;

    public IngestionScheduler(LogIngestionService service, DataSource dataSource, IngestionProperties props,
            IngestionMetrics metrics) {
        this.service = service;
        this.dataSource = dataSource;
        this.props = props;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${dpi.ingestion.interval:5m}", initialDelayString = "30s")
    public void run() {
        long lockKey = ("dpi-ingestion:" + props.streamName()).hashCode();
        try (Connection lockConnection = dataSource.getConnection()) {
            if (!tryLock(lockConnection, lockKey)) {
                log.debug("Another instance holds the ingestion lock; skipping this run");
                return;
            }
            try {
                IngestionResult result = metrics.runTimer().record(service::runOnce);
                log.info("Ingestion run finished: read={} chunksStored={} deadLettered={} batches={} caughtUp={} stoppedEarly={}",
                        result.recordsRead(), result.chunksStored(), result.deadLettered(), result.batches(),
                        result.caughtUp(), result.stoppedEarly());
            } finally {
                unlock(lockConnection, lockKey);
            }
        } catch (DependencyUnavailableException e) {
            metrics.runFailed();
            log.error("Ingestion run aborted, will resume from the last checkpoint: {}", e.getMessage());
        } catch (SQLException | RuntimeException e) {
            metrics.runFailed();
            log.error("Ingestion run failed", e);
        }
    }

    @PreDestroy
    void shutdown() {
        service.requestStop();
    }

    private static boolean tryLock(Connection c, long key) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
            ps.setLong(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    private static void unlock(Connection c, long key) {
        try (PreparedStatement ps = c.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            ps.setLong(1, key);
            ps.execute();
        } catch (SQLException e) {
            log.warn("Could not release ingestion advisory lock (it is released when the connection closes)");
        }
    }
}
