package tf.dpi.ai.ingestion;

/** A record that could not be ingested. {@code reason} must never contain log content. */
public record DeadLetter(String esDocId, String esIndex, Stage stage, String reason, int attempts) {

    public enum Stage { VALIDATE, MASK, CHUNK, EMBED, STORE }
}
