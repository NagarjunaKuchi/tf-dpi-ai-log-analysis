package tf.dpi.ai.exception;

/** The log source stayed unavailable after bounded retries. Carries no log content. */
public class SourceUnavailableException extends DependencyUnavailableException {

    public SourceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
