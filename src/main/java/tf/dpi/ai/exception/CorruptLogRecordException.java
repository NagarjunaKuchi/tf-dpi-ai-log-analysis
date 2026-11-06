package tf.dpi.ai.exception;

/** A source document could not be mapped to a valid log event. The message never contains log content. */
public class CorruptLogRecordException extends RuntimeException {

    private final String documentId;

    public CorruptLogRecordException(String documentId, String reason) {
        super(reason);
        this.documentId = documentId;
    }

    public String documentId() {
        return documentId;
    }
}
