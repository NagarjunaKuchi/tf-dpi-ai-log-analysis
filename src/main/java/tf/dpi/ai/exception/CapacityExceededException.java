package tf.dpi.ai.exception;

/** The analysis executor queue is full; the caller should retry later. */
public class CapacityExceededException extends RuntimeException {
    public CapacityExceededException(String message) {
        super(message);
    }
}
