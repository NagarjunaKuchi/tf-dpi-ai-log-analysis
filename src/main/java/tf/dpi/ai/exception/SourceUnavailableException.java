package tf.dpi.ai.exception;

/** A source or dependency stayed unavailable after bounded retries. Carries no log content. */
public class SourceUnavailableException extends RuntimeException {

    public SourceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
