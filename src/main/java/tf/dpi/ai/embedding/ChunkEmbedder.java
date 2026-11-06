package tf.dpi.ai.embedding;

import java.util.List;
import tf.dpi.ai.model.LogChunk;

/** Embeds sanitized chunks. Implementations must never embed unsanitized content. */
public interface ChunkEmbedder {

    /**
     * @throws tf.dpi.ai.exception.EmbeddingUnavailableException if the model is down or times out (retryable)
     * @throws tf.dpi.ai.exception.InvalidEmbeddingException     if the model returns an unusable vector
     */
    List<EmbeddedChunk> embed(List<LogChunk> chunks);
}
