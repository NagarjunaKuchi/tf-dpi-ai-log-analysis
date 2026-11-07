package tf.dpi.ai.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tf.dpi.ai.agent.dto.Confidence;
import tf.dpi.ai.agent.dto.Finding;
import tf.dpi.ai.evidence.Evidence.Nature;
import tf.dpi.ai.evidence.Evidence.SourceSystem;
import tf.dpi.ai.model.LogLevel;

/** The verifier must accept any error that is genuinely supported, while still catching mislabelled and unsupported claims. */
class OpenCategoryVerificationTest {

    private final EvidenceVerifier verifier = new EvidenceVerifier();

    private static Evidence log(String id, String text) {
        return new Evidence(id, SourceSystem.PGVECTOR_LOG_CHUNKS, Nature.CURRENT_OBSERVATION, "svc", Instant.parse("2026-11-07T23:00:00Z"),
                LogLevel.ERROR, text, id, List.of("es-" + id), 0.9, null);
    }

    private static Evidence runbook(String id, String text) {
        return new Evidence(id, SourceSystem.RUNBOOK_KB, Nature.HISTORICAL_KNOWLEDGE, "svc", null, null, text, id, List.of(), 0.9, "UNVERIFIED");
    }

    private static Finding finding(String category, String quote, Confidence c, String... ids) {
        return new Finding("statement", category, List.of(ids), List.of(quote), c);
    }

    @Test
    void anErrorWithNoConfiguredSignatureIsVerifiedUnderTheModelsOwnLabel() {
        Evidence e = log("E-1", "ERROR java.lang.NumberFormatException: For input string: \"12a\" while parsing quota");
        var r = verifier.verify(List.of(finding("invalid number format", "NumberFormatException", Confidence.MEDIUM, "E-1")), List.of(e));
        assertThat(r.rejected()).isEmpty();
        assertThat(r.verified()).singleElement().satisfies(f -> assertThat(f.category()).isEqualTo("INVALID_NUMBER_FORMAT"));
    }

    @Test
    void otherAndUnknownLabelsAreAcceptedOnTheGenericRulesAlone() {
        Evidence e = log("E-1", "ERROR RestServiceException: IDR-IDC-007 No Record(s) found");
        for (String label : new String[] {"OTHER", null, "", "MISSING_RECORD"}) {
            var r = verifier.verify(List.of(finding(label, "IDR-IDC-007", Confidence.LOW, "E-1")), List.of(e));
            assertThat(r.verified()).as("label=" + label).hasSize(1);
        }
    }

    @Test
    void aKnownLabelStillNeedsItsSignatureInTheCitedCurrentEvidence() {
        Evidence e = log("E-1", "ERROR java.lang.NumberFormatException: For input string: \"12a\"");
        var r = verifier.verify(List.of(finding("OUT_OF_MEMORY", "NumberFormatException", Confidence.HIGH, "E-1")), List.of(e));
        assertThat(r.verified()).isEmpty();
        assertThat(r.rejected()).singleElement().satisfies(x -> assertThat(x.reason()).contains("OUT_OF_MEMORY").contains("signature absent"));
    }

    @Test
    void genericRulesStillApplyToOpenLabels() {
        Evidence current = log("E-1", "ERROR something broke in the quota job");
        Evidence kb = runbook("K-1", "Runbook: quota job failures are usually caused by a stale cache");
        // quoted term not present in the cited evidence
        assertThat(verifier.verify(List.of(finding("QUOTA_FAILURE", "stale cache", Confidence.LOW, "E-1")), List.of(current, kb)).rejected()).hasSize(1);
        // supported only by a runbook: knowledge cannot prove a present fault
        assertThat(verifier.verify(List.of(finding("QUOTA_FAILURE", "stale cache", Confidence.LOW, "K-1")), List.of(current, kb)).rejected())
                .singleElement().satisfies(x -> assertThat(x.reason()).contains("historical knowledge"));
        // unknown evidence id
        assertThat(verifier.verify(List.of(finding("QUOTA_FAILURE", "x", Confidence.LOW, "E-404")), List.of(current)).rejected()).hasSize(1);
    }

    @Test
    void highConfidenceStillNeedsTwoCurrentItemsForAnyLabel() {
        Evidence a = log("E-1", "ERROR quota job failed: NumberFormatException");
        Evidence b = log("E-2", "ERROR quota job retry failed: NumberFormatException");
        var one = verifier.verify(List.of(finding("INVALID_NUMBER_FORMAT", "NumberFormatException", Confidence.HIGH, "E-1")), List.of(a, b));
        assertThat(one.verified().get(0).confidence()).isEqualTo(Confidence.MEDIUM);
        var two = verifier.verify(List.of(finding("INVALID_NUMBER_FORMAT", "NumberFormatException", Confidence.HIGH, "E-1", "E-2")), List.of(a, b));
        assertThat(two.verified().get(0).confidence()).isEqualTo(Confidence.HIGH);
    }

    @Test
    void customSignaturesGuardTheirOwnLabelOnly() {
        var v = new EvidenceVerifier(tf.dpi.ai.agent.FailureSignatures.of(false,
                List.of(new tf.dpi.ai.agent.FailureSignatures.Definition("QUOTA_EXCEEDED", "(?i)quota exceeded"))));
        Evidence e = log("E-1", "ERROR upload failed: disk quota is full");
        assertThat(v.verify(List.of(finding("QUOTA_EXCEEDED", "disk quota", Confidence.LOW, "E-1")), List.of(e)).rejected()).hasSize(1);
        assertThat(v.verify(List.of(finding("OUT_OF_MEMORY", "disk quota", Confidence.LOW, "E-1")), List.of(e)).verified()).hasSize(1);
    }
}
