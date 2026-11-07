package tf.dpi.ai.evidence;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import tf.dpi.ai.agent.FailureSignatures;
import tf.dpi.ai.agent.dto.Confidence;
import tf.dpi.ai.agent.dto.Finding;
import tf.dpi.ai.agent.dto.VerificationResult;
import tf.dpi.ai.agent.dto.VerificationResult.RejectedFinding;

/**
 * Deterministic gate between the LLM's claims and the report. No model is consulted. A finding survives only if:
 * it cites evidence that was actually retrieved in this run; at least one cited item is a CURRENT observation
 * (runbooks and past incidents cannot prove a present fault); it carries at least one quoted term and every quoted term
 * appears verbatim in the cited evidence; and, when its category is the name of a configured failure signature, that
 * signature appears in the cited current evidence. Any other category is accepted on the generic rules alone, so the
 * verifier does not limit which errors can be analysed. HIGH confidence additionally needs two distinct current items,
 * otherwise it is downgraded. The category is normalised (UPPER_SNAKE_CASE, bounded) because model output is untrusted.
 */
public final class EvidenceVerifier {

    private final FailureSignatures signatures;

    public EvidenceVerifier() {
        this(FailureSignatures.builtIn());
    }

    public EvidenceVerifier(FailureSignatures signatures) {
        this.signatures = signatures;
    }

    public FailureSignatures signatures() {
        return signatures;
    }

    public VerificationResult verify(List<Finding> findings, List<Evidence> pool) {
        Map<String, Evidence> byId = pool.stream()
                .collect(Collectors.toMap(Evidence::id, Function.identity(), (a, b) -> a));
        List<Finding> verified = new ArrayList<>();
        List<RejectedFinding> rejected = new ArrayList<>();
        for (Finding f : findings) {
            String reason = reject(f, byId);
            if (reason != null) {
                rejected.add(new RejectedFinding(f, reason));
                continue;
            }
            verified.add(capConfidence(normalised(f), byId));
        }
        return new VerificationResult(verified, rejected);
    }

    private String reject(Finding f, Map<String, Evidence> byId) {
        if (f.statement() == null || f.statement().isBlank()) {
            return "empty statement";
        }
        if (f.evidenceIds().isEmpty()) {
            return "cites no evidence";
        }
        List<Evidence> cited = new ArrayList<>();
        for (String id : new LinkedHashSet<>(f.evidenceIds())) {
            Evidence e = byId.get(id);
            if (e == null) {
                return "cites unknown evidence id " + id;
            }
            cited.add(e);
        }
        List<Evidence> current = cited.stream().filter(Evidence::isCurrent).toList();
        if (current.isEmpty()) {
            return "supported only by historical knowledge, not by a current observation";
        }
        List<String> terms = f.quotedTerms().stream().filter(t -> t != null && !t.isBlank()).toList();
        if (terms.isEmpty()) {
            return "provides no quoted term that can be checked against the evidence";
        }
        String citedText = cited.stream().map(Evidence::excerpt).collect(Collectors.joining("\n")).toLowerCase();
        for (String t : terms) {
            if (!citedText.contains(t.strip().toLowerCase())) {
                return "quoted term not found in cited evidence";
            }
        }
        String category = FailureSignatures.normalizeCategory(f.category());
        if (signatures.isKnown(category)) {
            String currentText = current.stream().map(Evidence::excerpt).collect(Collectors.joining("\n"));
            if (!signatures.matches(category, currentText)) {
                return "category " + category + " signature absent from cited current evidence";
            }
        }
        return null;
    }

    private static Finding normalised(Finding f) {
        return new Finding(f.statement(), FailureSignatures.normalizeCategory(f.category()), f.evidenceIds(), f.quotedTerms(),
                f.confidence());
    }

    private Finding capConfidence(Finding f, Map<String, Evidence> byId) {
        if (f.confidence() != Confidence.HIGH) {
            return f;
        }
        Set<String> currentIds = f.evidenceIds().stream().filter(id -> byId.get(id).isCurrent())
                .collect(Collectors.toSet());
        return currentIds.size() >= 2 ? f
                : new Finding(f.statement(), f.category(), f.evidenceIds(), f.quotedTerms(), Confidence.MEDIUM);
    }
}
