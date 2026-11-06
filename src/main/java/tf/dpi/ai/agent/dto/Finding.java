package tf.dpi.ai.agent.dto;

import java.util.List;

/**
 * One claim produced by root-cause analysis. {@code evidenceIds} must reference retrieved evidence and every entry of
 * {@code quotedTerms} must appear verbatim (case-insensitive) in the cited evidence, or the verifier rejects the claim.
 *
 * @param category a {@link tf.dpi.ai.agent.Scenario} name, or OTHER
 */
public record Finding(String statement, String category, List<String> evidenceIds, List<String> quotedTerms,
        Confidence confidence) implements java.io.Serializable {

    public Finding {
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        quotedTerms = quotedTerms == null ? List.of() : List.copyOf(quotedTerms);
        confidence = confidence == null ? Confidence.LOW : confidence;
    }
}
