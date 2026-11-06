package tf.dpi.ai.runbook;

import java.util.UUID;

/** Outcome of one ingest call. {@code unchanged} means the sanitized content equals the active version. */
public record KnowledgeIngestionResult(UUID docId, String docKey, int version, int chunkCount, boolean unchanged,
        String validationStatus) {
}
