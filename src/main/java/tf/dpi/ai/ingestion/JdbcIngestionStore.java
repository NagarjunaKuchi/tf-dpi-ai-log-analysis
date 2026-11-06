package tf.dpi.ai.ingestion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tf.dpi.ai.embedding.EmbeddedChunk;
import tf.dpi.ai.embedding.EmbeddingProperties;
import tf.dpi.ai.embedding.StoredVectorLookup;
import tf.dpi.ai.exception.DependencyUnavailableException;
import tf.dpi.ai.model.LogChunk;

/**
 * PostgreSQL / pgvector persistence for ingestion: checkpoints, dead letters, and log-chunk vectors in the
 * Spring AI PgVectorStore table layout. {@link #commit} runs everything in one transaction.
 */
public class JdbcIngestionStore implements CheckpointStore, BatchCommitter, StoredVectorLookup {

    private static final String UPSERT_VECTOR = """
            INSERT INTO log_chunk_vectors (id, content, metadata, embedding)
            VALUES (?, ?, ?::jsonb, ?::vector)
            ON CONFLICT (id) DO UPDATE SET content = EXCLUDED.content, metadata = EXCLUDED.metadata,
                embedding = EXCLUDED.embedding, ingested_at = now()""";

    private static final String UPSERT_DEAD_LETTER = """
            INSERT INTO ingestion_dead_letter (stream_name, es_doc_id, es_index, failure_stage, failure_reason, attempts)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (stream_name, es_doc_id) DO UPDATE SET
                attempts = ingestion_dead_letter.attempts + EXCLUDED.attempts,
                failure_stage = EXCLUDED.failure_stage, failure_reason = EXCLUDED.failure_reason,
                last_failed_at = now(), resolved = FALSE""";

    private static final String UPSERT_CHECKPOINT = """
            INSERT INTO ingestion_checkpoint (stream_name, last_timestamp, last_sort_values)
            VALUES (?, ?, ?::jsonb)
            ON CONFLICT (stream_name) DO UPDATE SET last_timestamp = EXCLUDED.last_timestamp,
                last_sort_values = EXCLUDED.last_sort_values, updated_at = now()""";

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final EmbeddingProperties embedding;

    public JdbcIngestionStore(JdbcTemplate jdbc, PlatformTransactionManager txManager, ObjectMapper json,
            EmbeddingProperties embedding) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
        this.tx = new TransactionTemplate(txManager);
        this.json = json;
        this.embedding = embedding;
    }

    @Override
    public Optional<Checkpoint> load(String streamName) {
        return guard(() -> jdbc.query(
                "SELECT last_timestamp, last_sort_values::text AS sort_values FROM ingestion_checkpoint WHERE stream_name = ?",
                rs -> {
                    if (!rs.next()) {
                        return Optional.<Checkpoint>empty();
                    }
                    try {
                        List<String> key = json.readValue(rs.getString("sort_values"), new TypeReference<>() { });
                        return Optional.of(new Checkpoint(rs.getTimestamp("last_timestamp").toInstant(), key));
                    } catch (JsonProcessingException e) {
                        throw new IllegalStateException("Corrupt checkpoint row for stream " + streamName, e);
                    }
                }, streamName));
    }

    @Override
    public void commit(String streamName, List<EmbeddedChunk> chunks, List<DeadLetter> deadLetters, Checkpoint next) {
        guard(() -> {
            tx.executeWithoutResult(status -> {
                List<Object[]> vectorRows = new ArrayList<>();
                for (EmbeddedChunk c : chunks) {
                    if (c.alreadyStored()) {
                        continue;
                    }
                    vectorRows.add(new Object[] {rowId(c.chunk().chunkId()), c.chunk().content(),
                            metadataJson(c), vectorLiteral(c.embedding())});
                }
                if (!vectorRows.isEmpty()) {
                    jdbc.batchUpdate(UPSERT_VECTOR, vectorRows);
                }
                for (DeadLetter d : deadLetters) {
                    jdbc.update(UPSERT_DEAD_LETTER, streamName, d.esDocId(), d.esIndex(), d.stage().name(),
                            d.reason(), d.attempts());
                }
                jdbc.update(UPSERT_CHECKPOINT, streamName, Timestamp.from(next.lastTimestamp()), toJson(next.sortKey()));
            });
            return null;
        });
    }

    @Override
    public Map<String, String> storedHashes(Collection<String> chunkIds, String modelName, String modelVersion) {
        if (chunkIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> byUuid = new HashMap<>();
        for (String id : chunkIds) {
            byUuid.put(rowId(id), id);
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("ids", new ArrayList<>(byUuid.keySet()))
                .addValue("model", modelName).addValue("version", modelVersion);
        Map<String, String> out = new HashMap<>();
        guard(() -> {
            named.query("""
                    SELECT id, metadata ->> 'contentSha256' AS sha FROM log_chunk_vectors
                    WHERE id IN (:ids) AND metadata ->> 'embeddingModel' = :model AND metadata ->> 'embeddingVersion' = :version""",
                    params, rs -> {
                        out.put(byUuid.get(rs.getObject("id", UUID.class)), rs.getString("sha"));
                    });
            return null;
        });
        return out;
    }

    /** Deletes vectors whose time range ended before the cutoff (rolling retention window). */
    public int purgeOlderThan(java.time.Instant cutoff) {
        return guard(() -> jdbc.update(
                "DELETE FROM log_chunk_vectors WHERE (metadata ->> 'toEpochMs')::bigint < ?", cutoff.toEpochMilli()));
    }

    public static UUID rowId(String chunkId) {
        return UUID.nameUUIDFromBytes(chunkId.getBytes(StandardCharsets.UTF_8));
    }

    private String metadataJson(EmbeddedChunk e) {
        LogChunk c = e.chunk();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("chunkId", c.chunkId());
        m.put("service", c.service());
        m.put("level", c.level().name());
        m.put("fromEpochMs", c.fromTimestamp().toEpochMilli());
        m.put("toEpochMs", c.toTimestamp().toEpochMilli());
        m.put("errorCode", c.errorCode());
        m.put("traceId", c.traceId());
        m.put("host", c.host());
        m.put("sourceIds", c.sourceIds());
        m.put("contentSha256", e.contentSha256());
        m.put("embeddingModel", embedding.modelName());
        m.put("embeddingVersion", embedding.modelVersion());
        c.metadata().forEach((k, v) -> m.put("chunking_" + k, v));
        return toJson(m);
    }

    private String toJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise value to JSON", e);
        }
    }

    public static String vectorLiteral(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 9).append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }

    /** Maps database outages to DependencyUnavailableException so callers retry/abort instead of dead-lettering. */
    private static <T> T guard(java.util.function.Supplier<T> action) {
        try {
            return action.get();
        } catch (DataAccessResourceFailureException | TransientDataAccessException e) {
            throw new DependencyUnavailableException("PostgreSQL unavailable", e);
        }
    }
}
