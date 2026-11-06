package tf.dpi.ai.scheduler;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tf.dpi.ai.agent.AgentProperties;
import tf.dpi.ai.persistence.AnalysisRepository;

/**
 * Analyses are not resumable: a restart loses in-flight work. This marks QUEUED/RUNNING rows that have not progressed for
 * longer than twice the agent timeout (plus a margin) as FAILED/INTERRUPTED so callers see an explicit outcome and can
 * resubmit. The age threshold (not "everything RUNNING") keeps it safe with several replicas.
 */
@Component
public class StaleAnalysisRecovery {

    private static final Logger log = LoggerFactory.getLogger(StaleAnalysisRecovery.class);
    private final AnalysisRepository repo;
    private final Duration threshold;

    public StaleAnalysisRecovery(AnalysisRepository repo, AgentProperties props) {
        this.repo = repo;
        this.threshold = props.timeout().multipliedBy(2).plus(Duration.ofMinutes(1));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        sweep();
    }

    @Scheduled(fixedDelayString = "${dpi.agent.stale-sweep-interval:PT5M}", initialDelayString = "PT5M")
    public void sweep() {
        try {
            int n = repo.failStale(threshold);
            if (n > 0) {
                log.warn("marked {} stale analyses as INTERRUPTED", n);
            }
        } catch (RuntimeException e) {
            log.warn("stale analysis sweep failed: {}", e.getClass().getSimpleName());
        }
    }
}
