package tf.dpi.ai.dto;

import java.util.UUID;

public record AnalysisAcceptedDto(UUID analysisId, String status, String statusUrl) {
}
