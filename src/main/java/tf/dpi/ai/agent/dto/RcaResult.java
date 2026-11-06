package tf.dpi.ai.agent.dto;

import java.util.List;

/** Structured output of the root-cause-analysis step (LLM output; untrusted until verified). */
public record RcaResult(String summary, List<Finding> findings, List<String> recommendedChecks,
        List<String> openQuestions) implements java.io.Serializable {

    public RcaResult {
        findings = findings == null ? List.of() : List.copyOf(findings);
        recommendedChecks = recommendedChecks == null ? List.of() : List.copyOf(recommendedChecks);
        openQuestions = openQuestions == null ? List.of() : List.copyOf(openQuestions);
    }
}
