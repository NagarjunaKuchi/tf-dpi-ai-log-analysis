package tf.dpi.ai.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.junit.jupiter.api.condition.EnabledIf;
import tf.dpi.ai.support.TestDatabase;
import tf.dpi.ai.embedding.EmbeddedChunk;
import tf.dpi.ai.embedding.EmbeddingProperties;
import tf.dpi.ai.model.LogChunk;
import tf.dpi.ai.model.LogLevel;

/**
 * Runs the real Flyway migration and JDBC store against PostgreSQL + pgvector. Skipped automatically when Docker is
 * not available (it was NOT executed in the authoring environment).
 */
@EnabledIf(value = "tf.dpi.ai.support.TestDatabase#available", disabledReason = "no Docker and DPI_TEST_JDBC_URL unset")
class JdbcIngestionStoreIntegrationTest {

    static JdbcTemplate jdbc;
    static JdbcIngestionStore store;
    static final EmbeddingProperties EMBEDDING = new EmbeddingProperties("bge-m3", "1", 1024, 8, Duration.ofSeconds(5));

    @BeforeAll
    static void migrate() {
        javax.sql.DataSource ds = TestDatabase.migrated();
        jdbc = new JdbcTemplate(ds);
        store = new JdbcIngestionStore(jdbc, new DataSourceTransactionManager(ds), new ObjectMapper(), EMBEDDING);
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM log_chunk_vectors");
        jdbc.update("DELETE FROM ingestion_dead_letter");
        jdbc.update("DELETE FROM ingestion_checkpoint");
    }

    private static float[] vector(float seed) {
        float[] v = new float[1024];
        v[0] = seed;
        v[1] = 1f;
        return v;
    }

    private static EmbeddedChunk chunk(String id, String content, Instant from, float seed) {
        LogChunk c = new LogChunk(id, "db-pool", LogLevel.ERROR, from, from.plusSeconds(5), "DB-001", "t1", "h1",
                content, List.of("e1", "e2"), Map.of("tokenEstimate", "10"));
        return new EmbeddedChunk(c, "sha-" + content, vector(seed));
    }

    @Test
    void commitStoresVectorsDeadLettersAndCheckpointAtomically() {
        Instant t = Instant.parse("2026-11-06T10:00:00Z");
        Checkpoint next = new Checkpoint(t, List.of(Long.toString(t.toEpochMilli()), "e2"));
        store.commit("s", List.of(chunk("c1", "alpha", t, 0.1f)),
                List.of(new DeadLetter("bad", "idx", DeadLetter.Stage.VALIDATE, "timestamp missing", 1)), next);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM log_chunk_vectors", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ingestion_dead_letter", Integer.class)).isEqualTo(1);
        assertThat(store.load("s")).get().satisfies(cp -> {
            assertThat(cp.lastTimestamp()).isEqualTo(t);
            assertThat(cp.sortKey()).containsExactly(Long.toString(t.toEpochMilli()), "e2");
        });
        assertThat(jdbc.queryForObject("SELECT metadata ->> 'service' FROM log_chunk_vectors", String.class))
                .isEqualTo("db-pool");
    }

    @Test
    void failedCommitRollsBackEverythingIncludingTheCheckpoint() {
        Instant t = Instant.parse("2026-11-06T10:00:00Z");
        float[] wrongDims = new float[3]; // violates vector(1024)
        EmbeddedChunk bad = new EmbeddedChunk(chunk("c1", "alpha", t, 0.1f).chunk(), "sha", wrongDims);
        assertThatThrownBy(() -> store.commit("s", List.of(chunk("c0", "beta", t, 0.2f), bad), List.of(),
                new Checkpoint(t, List.of("1", "x")))).isInstanceOf(RuntimeException.class);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM log_chunk_vectors", Integer.class)).isZero();
        assertThat(store.load("s")).isEmpty();
    }

    @Test
    void upsertIsIdempotentAndStoredHashesReflectModelAndVersion() {
        Instant t = Instant.parse("2026-11-06T10:00:00Z");
        Checkpoint cp = new Checkpoint(t, List.of("1", "x"));
        store.commit("s", List.of(chunk("c1", "alpha", t, 0.1f)), List.of(), cp);
        store.commit("s", List.of(chunk("c1", "alpha", t, 0.1f)), List.of(), cp);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM log_chunk_vectors", Integer.class)).isEqualTo(1);
        assertThat(store.storedHashes(List.of("c1", "missing"), "bge-m3", "1")).containsEntry("c1", "sha-alpha")
                .doesNotContainKey("missing");
        assertThat(store.storedHashes(List.of("c1"), "bge-m3", "2")).isEmpty();
        assertThat(store.storedHashes(List.of("c1"), "other-model", "1")).isEmpty();
    }

    @Test
    void similaritySearchWithMetadataFilterWorksThroughHnswIndex() {
        Instant t = Instant.parse("2026-11-06T10:00:00Z");
        store.commit("s", List.of(chunk("c1", "alpha", t, 0.1f), chunk("c2", "beta", t, 0.9f)), List.of(),
                new Checkpoint(t, List.of("1", "x")));
        String nearest = jdbc.queryForObject("""
                SELECT metadata ->> 'chunkId' FROM log_chunk_vectors
                WHERE metadata ->> 'service' = 'db-pool' AND metadata ->> 'embeddingModel' = 'bge-m3'
                ORDER BY embedding <=> ?::vector LIMIT 1""", String.class,
                JdbcIngestionStore.vectorLiteral(vector(0.88f)));
        assertThat(nearest).isEqualTo("c2");
    }

    @Test
    void deadLetterReoccurrenceIncrementsAttempts() {
        Instant t = Instant.parse("2026-11-06T10:00:00Z");
        Checkpoint cp = new Checkpoint(t, List.of("1", "x"));
        DeadLetter d = new DeadLetter("bad", "idx", DeadLetter.Stage.MASK, "masking failed", 1);
        store.commit("s", List.of(), List.of(d), cp);
        store.commit("s", List.of(), List.of(d), cp);
        assertThat(jdbc.queryForObject("SELECT attempts FROM ingestion_dead_letter WHERE es_doc_id='bad'", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void retentionPurgeRemovesOnlyExpiredVectors() {
        Instant old = Instant.parse("2026-01-01T00:00:00Z");
        Instant fresh = Instant.parse("2026-11-06T10:00:00Z");
        store.commit("s", List.of(chunk("old", "o", old, 0.1f), chunk("new", "n", fresh, 0.2f)), List.of(),
                new Checkpoint(fresh, List.of("1", "x")));
        int removed = store.purgeOlderThan(Instant.parse("2026-10-01T00:00:00Z"));
        assertThat(removed).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM log_chunk_vectors", Integer.class)).isEqualTo(1);
    }
}
