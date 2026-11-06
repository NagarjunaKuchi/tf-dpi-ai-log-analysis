package tf.dpi.ai.ingestion;

/** Outcome of one ingestion run. */
public record IngestionResult(long recordsRead, long chunksStored, long deadLettered, int batches, boolean caughtUp,
        boolean stoppedEarly) {
}
