package tf.dpi.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tf.dpi.ai.runbook.KnowledgeKind;

public record KnowledgeRequestDto(
        @NotBlank @Size(max = 128) @Pattern(regexp = "^[A-Za-z0-9_.\\-]+$") String docKey,
        @NotNull KnowledgeKind kind,
        @NotBlank @Size(max = 300) String title,
        @NotBlank @Size(max = 500) String sourceRef,
        @NotBlank @Size(max = 200_000) String content) {
}
