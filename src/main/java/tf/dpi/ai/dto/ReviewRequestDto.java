package tf.dpi.ai.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tf.dpi.ai.analysis.ReviewDecision;

public record ReviewRequestDto(@NotNull ReviewDecision decision, @Size(max = 2000) String comment) {
}
