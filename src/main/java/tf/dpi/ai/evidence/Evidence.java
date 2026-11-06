package tf.dpi.ai.evidence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import tf.dpi.ai.model.LogLevel;

/**
 * One retrieved piece of evidence. {@code excerpt} is always sanitized text. Current observations come from logs;
 * historical knowledge (runbooks, past incidents) is kept strictly separate and can never prove a current fault.
 *
 * @param id               stable, derived from the source (so the same source always yields the same id)
 * @param sourceRef        chunk id / Elasticsearch document id / knowledge document reference
 * @param sourceIds        underlying Elasticsearch document ids for log evidence
 * @param validationStatus knowledge only: UNVERIFIED or ENGINEER_VALIDATED; null for logs
 */
public record Evidence(
        String id,
        SourceSystem sourceSystem,
        Nature nature,
        String service,
        Instant timestamp,
        LogLevel level,
        String excerpt,
        String sourceRef,
        List<String> sourceIds,
        double score,
        String validationStatus) implements java.io.Serializable {

    public enum SourceSystem { PGVECTOR_LOG_CHUNKS, ELASTICSEARCH_LIVE, RUNBOOK_KB, INCIDENT_KB }

    public enum Nature { CURRENT_OBSERVATION, HISTORICAL_KNOWLEDGE }

    public Evidence {
        sourceIds = sourceIds == null ? List.of() : List.copyOf(sourceIds);
    }

    public boolean isCurrent() {
        return nature == Nature.CURRENT_OBSERVATION;
    }

    public static String idFor(SourceSystem system, String sourceRef) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest((system.name() + ":" + sourceRef).getBytes(StandardCharsets.UTF_8));
            return "E-" + HexFormat.of().formatHex(d, 0, 5);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
