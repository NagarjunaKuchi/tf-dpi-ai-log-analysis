package tf.dpi.ai.runbook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tf.dpi.ai.embedding.EmbeddingProperties;
import tf.dpi.ai.embedding.TextEmbedder;
import tf.dpi.ai.exception.DependencyUnavailableException;
import tf.dpi.ai.ingestion.JdbcIngestionStore;
import tf.dpi.ai.masking.MaskingEngine;

/**
 * Ingests runbooks / past incidents / procedures. Content is masked before it is chunked, embedded or stored (a masking
 * failure aborts the ingest — fail closed). Documents are versioned per {@code docKey}: re-ingesting identical sanitized
 * content is a no-op, changed content creates version N+1 and deactivates N. New versions are always UNVERIFIED until an
 * engineer explicitly validates them. Document row, vectors and the supersession of the previous version commit atomically.
 */
public class KnowledgeIngestionService {

    private static final String INSERT_DOC = """
            INSERT INTO knowledge_document (doc_id, doc_key, version, kind, title, source_ref, content_sha256,
                validation_status, active) VALUES (?, ?, ?, ?, ?, ?, ?, 'UNVERIFIED', TRUE)""";
    private static final String INSERT_VECTOR = """
            INSERT INTO knowledge_vectors (id, content, metadata, embedding) VALUES (?, ?, ?::jsonb, ?::vector)""";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final MaskingEngine masking;
    private final TextEmbedder embedder;
    private final EmbeddingProperties embedding;
    private final KnowledgeChunker chunker;

    public KnowledgeIngestionService(JdbcTemplate jdbc, PlatformTransactionManager txManager, ObjectMapper json,
            MaskingEngine masking, TextEmbedder embedder, EmbeddingProperties embedding, KnowledgeChunker chunker) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.json = json;
        this.masking = masking;
        this.embedder = embedder;
        this.embedding = embedding;
        this.chunker = chunker;
    }

    public KnowledgeIngestionResult ingest(String docKey, KnowledgeKind kind, String title, String sourceRef,
            String rawContent) {
        require(docKey, "docKey");
        require(title, "title");
        require(sourceRef, "sourceRef");
        require(rawContent, "content");
        String safeTitle = masking.maskText(title);
        String safeRef = masking.maskText(sourceRef);
        String sanitized = masking.maskText(rawContent);
        String sha = sha256(sanitized);

        try {
            Map<String, Object> latest = latestVersion(docKey);
            if (latest != null && sha.equals(String.valueOf(latest.get("content_sha256")).trim())
                    && Boolean.TRUE.equals(latest.get("active"))) {
                return new KnowledgeIngestionResult((UUID) latest.get("doc_id"), docKey,
                        ((Number) latest.get("version")).intValue(), 0, true,
                        String.valueOf(latest.get("validation_status")));
            }
            int version = latest == null ? 1 : ((Number) latest.get("version")).intValue() + 1;

            List<String> chunks = chunker.chunk(sanitized);
            if (chunks.isEmpty()) {
                throw new IllegalArgumentException("Knowledge document has no content after sanitisation");
            }
            // embeddings are computed outside the DB transaction so a slow model never holds locks
            List<float[]> vectors = embedder.embedTexts(chunks);
            UUID docId = UUID.randomUUID();

            tx.executeWithoutResult(status -> {
                jdbc.update("UPDATE knowledge_document SET active = FALSE WHERE doc_key = ? AND active", docKey);
                jdbc.update("UPDATE knowledge_vectors SET metadata = jsonb_set(metadata, '{active}', 'false'::jsonb) "
                        + "WHERE metadata ->> 'docKey' = ?", docKey);
                jdbc.update(INSERT_DOC, docId, docKey, version, kind.name(), safeTitle, safeRef, sha);
                List<Object[]> rows = new ArrayList<>(chunks.size());
                for (int i = 0; i < chunks.size(); i++) {
                    rows.add(new Object[] {
                            JdbcIngestionStore.rowId(docKey + "@v" + version + "#" + i), chunks.get(i),
                            metadata(docKey, version, i, kind, safeTitle, safeRef, "UNVERIFIED", true),
                            JdbcIngestionStore.vectorLiteral(vectors.get(i))});
                }
                jdbc.batchUpdate(INSERT_VECTOR, rows);
            });
            return new KnowledgeIngestionResult(docId, docKey, version, chunks.size(), false, "UNVERIFIED");
        } catch (DataAccessException e) {
            throw new DependencyUnavailableException("Database unavailable while ingesting knowledge document", e);
        }
    }

    /** Records an engineer's validation of one version. Returns false when the version does not exist. */
    public boolean validate(String docKey, int version, String validatedBy) {
        require(validatedBy, "validatedBy");
        try {
            Boolean ok = tx.execute(status -> {
                int n = jdbc.update("""
                        UPDATE knowledge_document SET validation_status = 'ENGINEER_VALIDATED', validated_by = ?,
                            validated_at = now() WHERE doc_key = ? AND version = ?""", validatedBy, docKey, version);
                if (n == 0) {
                    return false;
                }
                jdbc.update("""
                        UPDATE knowledge_vectors SET metadata = jsonb_set(metadata, '{validationStatus}',
                            '"ENGINEER_VALIDATED"'::jsonb)
                        WHERE metadata ->> 'docKey' = ? AND (metadata ->> 'version')::int = ?""", docKey, version);
                return true;
            });
            return Boolean.TRUE.equals(ok);
        } catch (DataAccessException e) {
            throw new DependencyUnavailableException("Database unavailable while validating knowledge document", e);
        }
    }

    private Map<String, Object> latestVersion(String docKey) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT doc_id, version, content_sha256, active, validation_status FROM knowledge_document "
                        + "WHERE doc_key = ? ORDER BY version DESC LIMIT 1", docKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String metadata(String docKey, int version, int index, KnowledgeKind kind, String title, String ref,
            String status, boolean active) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("docKey", docKey);
        m.put("version", version);
        m.put("chunkIndex", index);
        m.put("kind", kind.name());
        m.put("title", title);
        m.put("sourceRef", ref);
        m.put("validationStatus", status);
        m.put("active", active);
        m.put("embeddingModel", embedding.modelName());
        m.put("embeddingVersion", embedding.modelVersion());
        try {
            return json.writeValueAsString(m);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise metadata", e);
        }
    }

    private static void require(String v, String name) {
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
