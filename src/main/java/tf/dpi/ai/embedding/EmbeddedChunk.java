package tf.dpi.ai.embedding;

import tf.dpi.ai.model.LogChunk;

/**
 * A chunk paired with its vector.
 *
 * @param embedding null when an identical vector (same content hash, model and version) is already stored, so no
 *                  new embedding was generated and nothing needs to be written
 */
public record EmbeddedChunk(LogChunk chunk, String contentSha256, float[] embedding) {

    public boolean alreadyStored() {
        return embedding == null;
    }
}
