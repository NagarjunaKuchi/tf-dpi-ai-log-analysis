package tf.dpi.ai.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tf.dpi.ai.analysis.AnalysisStatus;
import tf.dpi.ai.analysis.ReviewDecision;
import tf.dpi.ai.exception.DependencyUnavailableException;
import tf.dpi.ai.report.IncidentReport;

/** JDBC persistence for analysis requests, reports, reviews and the audit trail. */
public class AnalysisRepository {

    public record AnalysisRow(UUID analysisId, String question, String requestedBy, AnalysisStatus status,
            String failureReason, Instant createdAt, Instant updatedAt) {
    }

    public record ReportRow(UUID analysisId, String reportJson, String reviewStatus, String reviewedBy,
            Instant reviewedAt, String reviewComment) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;

    public AnalysisRepository(JdbcTemplate jdbc, PlatformTransactionManager txManager, ObjectMapper json) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.json = json;
    }

    public void create(UUID id, String maskedQuestion, String user, String correlationId) {
        guard(() -> tx.executeWithoutResult(s -> {
            jdbc.update("INSERT INTO analysis_request (analysis_id, query_text, requested_by, correlation_id, status) "
                    + "VALUES (?, ?, ?, ?, 'QUEUED')", id, maskedQuestion, user, correlationId);
            audit(user, "ANALYSIS_SUBMITTED", id.toString(), correlationId, Map.of());
        }));
    }

    public void updateStatus(UUID id, AnalysisStatus status, String failureReason) {
        guard(() -> jdbc.update("UPDATE analysis_request SET status = ?, failure_reason = ?, updated_at = now() "
                + "WHERE analysis_id = ?", status.name(), failureReason, id));
    }

    /** Stores the report and final status atomically. */
    public void completeWithReport(UUID id, AnalysisStatus status, IncidentReport report, String actor,
            String correlationId) {
        String body = toJson(report);
        guard(() -> tx.executeWithoutResult(s -> {
            jdbc.update("INSERT INTO incident_report (analysis_id, report_json) VALUES (?, ?::jsonb) "
                    + "ON CONFLICT (analysis_id) DO NOTHING", id, body);
            jdbc.update("UPDATE analysis_request SET status = ?, updated_at = now() WHERE analysis_id = ?",
                    status.name(), id);
            audit(actor, "REPORT_GENERATED", id.toString(), correlationId, Map.of("outcome", report.outcome().name()));
        }));
    }

    public Optional<AnalysisRow> find(UUID id) {
        return guard(() -> jdbc.query("SELECT analysis_id, query_text, requested_by, status, failure_reason, created_at, "
                + "updated_at FROM analysis_request WHERE analysis_id = ?", (rs, n) -> new AnalysisRow(
                        rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        AnalysisStatus.valueOf(rs.getString(4)), rs.getString(5),
                        rs.getTimestamp(6).toInstant(), rs.getTimestamp(7).toInstant()), id).stream().findFirst());
    }

    public Optional<ReportRow> findReport(UUID id) {
        return guard(() -> jdbc.query("SELECT analysis_id, report_json::text, review_status, reviewed_by, reviewed_at, "
                + "review_comment FROM incident_report WHERE analysis_id = ?", (rs, n) -> new ReportRow(
                        rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getTimestamp(5) == null ? null : rs.getTimestamp(5).toInstant(), rs.getString(6)), id)
                .stream().findFirst());
    }

    public List<AnalysisRow> recent(int limit) {
        return guard(() -> jdbc.query("SELECT analysis_id, query_text, requested_by, status, failure_reason, created_at, "
                + "updated_at FROM analysis_request ORDER BY created_at DESC LIMIT ?", (rs, n) -> new AnalysisRow(
                        rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        AnalysisStatus.valueOf(rs.getString(4)), rs.getString(5),
                        rs.getTimestamp(6).toInstant(), rs.getTimestamp(7).toInstant()), limit));
    }

    /**
     * Records the human decision exactly once: the UPDATE only matches a report still PENDING_REVIEW, so concurrent or
     * repeated decisions cannot overwrite each other. Returns false when no pending report exists.
     */
    public boolean review(UUID id, ReviewDecision decision, String reviewer, String comment, String correlationId) {
        Boolean ok = guard(() -> tx.execute(s -> {
            int n = jdbc.update("UPDATE incident_report SET review_status = ?, reviewed_by = ?, reviewed_at = now(), "
                    + "review_comment = ? WHERE analysis_id = ? AND review_status = 'PENDING_REVIEW'",
                    decision.name(), reviewer, comment, id);
            if (n == 0) {
                return false;
            }
            if (decision == ReviewDecision.APPROVED) {
                jdbc.update("UPDATE analysis_request SET status = 'COMPLETED', updated_at = now() WHERE analysis_id = ?", id);
            }
            audit(reviewer, "REPORT_REVIEWED", id.toString(), correlationId, Map.of("decision", decision.name()));
            return true;
        }));
        return Boolean.TRUE.equals(ok);
    }

    public void audit(String actor, String action, String subject, String correlationId, Map<String, Object> detail) {
        jdbc.update("INSERT INTO audit_log (actor, action, subject, correlation_id, detail) VALUES (?, ?, ?, ?, ?::jsonb)",
                actor, action, subject, correlationId, toJson(detail));
    }

    private String toJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise value to JSON", e);
        }
    }

    private void guard(Runnable r) {
        guard(() -> {
            r.run();
            return null;
        });
    }

    private <T> T guard(java.util.function.Supplier<T> s) {
        try {
            return s.get();
        } catch (DataAccessException e) {
            throw new DependencyUnavailableException("Database unavailable", e);
        }
    }
}
