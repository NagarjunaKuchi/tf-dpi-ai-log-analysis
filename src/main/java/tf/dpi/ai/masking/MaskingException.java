package tf.dpi.ai.masking;

/**
 * Thrown when masking cannot be completed safely. Callers must treat this as "do not embed, do not send to LLM".
 * Messages never include the offending input.
 */
public class MaskingException extends RuntimeException {

    public MaskingException(String message) {
        super(message);
    }

    public MaskingException(String message, Throwable cause) {
        super(message, cause);
    }
}
