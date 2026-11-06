package tf.dpi.ai.agent.dto;

import java.util.List;

public record VerificationResult(List<Finding> verified, List<RejectedFinding> rejected) implements java.io.Serializable {

    public VerificationResult {
        verified = List.copyOf(verified);
        rejected = List.copyOf(rejected);
    }

    public record RejectedFinding(Finding finding, String reason) implements java.io.Serializable {
    }
}
