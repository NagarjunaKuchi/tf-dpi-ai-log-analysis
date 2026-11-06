package tf.dpi.ai.report;

import java.time.Instant;
import java.util.List;
import tf.dpi.ai.agent.dto.Finding;
import tf.dpi.ai.agent.dto.VerificationResult.RejectedFinding;
import tf.dpi.ai.evidence.Evidence;

/**
 * Structured incident report. Nothing here is executed automatically; {@code suggestedActions} are for an engineer
 * to evaluate, and {@code reviewStatus} stays PENDING_ENGINEER_REVIEW until a human decides.
 */
public record IncidentReport(
        String analysisId,
        String question,
        Instant generatedAt,
        Outcome outcome,
        String summary,
        List<String> impactedServices,
        List<TimelineEntry> timeline,
        List<Finding> verifiedFindings,
        List<RejectedFinding> rejectedClaims,
        List<Evidence> supportingEvidence,
        List<Evidence> relatedKnowledge,
        List<String> suggestedActions,
        List<String> openQuestions,
        List<String> limitations,
        String reviewStatus) implements java.io.Serializable {

    public enum Outcome { FINDINGS_VERIFIED, INSUFFICIENT_EVIDENCE, FAILED }

    public record TimelineEntry(Instant timestamp, String service, String level, String evidenceId, String text) implements java.io.Serializable {
    }
}
