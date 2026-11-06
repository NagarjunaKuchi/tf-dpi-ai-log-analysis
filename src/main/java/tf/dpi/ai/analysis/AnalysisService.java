package tf.dpi.ai.analysis;

import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.RunnableConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import tf.dpi.ai.agent.graph.AgentStateKeys;
import tf.dpi.ai.agent.graph.DpiAgentState;
import tf.dpi.ai.exception.CapacityExceededException;
import tf.dpi.ai.exception.ConflictException;
import tf.dpi.ai.exception.NotFoundException;
import tf.dpi.ai.masking.MaskingEngine;
import tf.dpi.ai.persistence.AnalysisRepository;
import tf.dpi.ai.persistence.AnalysisRepository.AnalysisRow;
import tf.dpi.ai.persistence.AnalysisRepository.ReportRow;
import tf.dpi.ai.report.IncidentReport;

/**
 * Runs analyses asynchronously on a bounded executor. The question is masked before it is stored or sent anywhere
 * (masking failure aborts the request). Results are only ever stored as reports for human review.
 */
public class AnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisService.class);

    private final AnalysisRepository repo;
    private final CompiledGraph<DpiAgentState> graph;
    private final MaskingEngine masking;
    private final ExecutorService executor;
    private final Clock clock;

    public AnalysisService(AnalysisRepository repo, CompiledGraph<DpiAgentState> graph, MaskingEngine masking,
            ExecutorService executor, Clock clock) {
        this.repo = repo;
        this.graph = graph;
        this.masking = masking;
        this.executor = executor;
        this.clock = clock;
    }

    public UUID submit(String question, String user, String correlationId) {
        String masked = masking.maskText(question); // MaskingException propagates: fail closed
        UUID id = UUID.randomUUID();
        repo.create(id, masked, user, correlationId);
        try {
            executor.execute(() -> run(id, masked, user, correlationId));
        } catch (RejectedExecutionException e) {
            repo.updateStatus(id, AnalysisStatus.FAILED, "REJECTED_CAPACITY");
            throw new CapacityExceededException("Analysis queue is full; retry later");
        }
        return id;
    }

    void run(UUID id, String maskedQuestion, String user, String correlationId) {
        MDC.put("correlationId", correlationId);
        MDC.put("analysisId", id.toString());
        try {
            repo.updateStatus(id, AnalysisStatus.RUNNING, null);
            DpiAgentState end = graph.invoke(java.util.Map.of(
                    AgentStateKeys.ANALYSIS_ID, id.toString(),
                    AgentStateKeys.QUESTION, maskedQuestion,
                    AgentStateKeys.STARTED_AT, clock.instant()),
                    RunnableConfig.builder().threadId(id.toString()).build()).orElseThrow();
            IncidentReport report = end.report();
            AnalysisStatus status = switch (report.outcome()) {
                case FINDINGS_VERIFIED -> AnalysisStatus.AWAITING_REVIEW;
                case INSUFFICIENT_EVIDENCE -> AnalysisStatus.INSUFFICIENT_EVIDENCE;
                case FAILED -> end.failure() != null && end.failure().startsWith("TIMEOUT")
                        ? AnalysisStatus.TIMED_OUT : AnalysisStatus.FAILED;
            };
            repo.completeWithReport(id, status, report, "agent", correlationId);
        } catch (RuntimeException e) {
            log.error("analysis {} crashed: {}", id, e.getClass().getSimpleName());
            try {
                repo.updateStatus(id, AnalysisStatus.FAILED, "INTERNAL_" + e.getClass().getSimpleName());
            } catch (RuntimeException secondary) {
                log.error("could not record failure for {}: {}", id, secondary.getClass().getSimpleName());
            }
        } finally {
            MDC.clear();
        }
    }

    public AnalysisRow get(UUID id) {
        return repo.find(id).orElseThrow(() -> new NotFoundException("analysis not found"));
    }

    public ReportRow report(UUID id) {
        get(id);
        return repo.findReport(id).orElseThrow(() -> new NotFoundException("report not available"));
    }

    public void review(UUID id, ReviewDecision decision, String comment, String reviewer, String correlationId) {
        report(id);
        String safeComment = comment == null ? null : masking.maskText(comment);
        if (!repo.review(id, decision, reviewer, safeComment, correlationId)) {
            throw new ConflictException("report has already been reviewed");
        }
    }
}
