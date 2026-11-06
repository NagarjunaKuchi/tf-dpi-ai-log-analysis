package tf.dpi.ai.exception;

/** The embedding model is unreachable or timed out. */
public class EmbeddingUnavailableException extends DependencyUnavailableException {

    public EmbeddingUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
