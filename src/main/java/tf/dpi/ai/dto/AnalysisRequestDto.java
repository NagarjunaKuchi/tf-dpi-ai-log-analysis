package tf.dpi.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AnalysisRequestDto(@NotBlank @Size(max = 2000) String question) {
}
