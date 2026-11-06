package tf.dpi.ai.exception;

/** The embedding model returned something unusable (wrong dimension, wrong count, non-finite values). */
public class InvalidEmbeddingException extends RuntimeException {

    public InvalidEmbeddingException(String message) {
        super(message);
    }
}
