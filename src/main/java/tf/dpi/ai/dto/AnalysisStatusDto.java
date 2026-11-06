package tf.dpi.ai.dto;

import java.time.Instant;
import java.util.UUID;

public record AnalysisStatusDto(UUID analysisId, String status, String failureReason, Instant createdAt,
        Instant updatedAt) {
}
