package tf.dpi.ai.vectorstore;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import tf.dpi.ai.analysis.AnalysisStatus;
import tf.dpi.ai.analysis.ReviewDecision;
import tf.dpi.ai.embedding.EmbeddingProperties;
import tf.dpi.ai.evidence.Evidence;
import tf.dpi.ai.ingestion.JdbcIngestionStore;
import tf.dpi.ai.masking.MaskingEngine;
import tf.dpi.ai.masking.MaskingPolicy;
import tf.dpi.ai.model.LogLevel;
import tf.dpi.ai.persistence.AnalysisRepository;
import tf.dpi.ai.report.IncidentReport;
import tf.dpi.ai.runbook.KnowledgeChunker;
import tf.dpi.ai.runbook.KnowledgeIngestionService;
import tf.dpi.ai.runbook.KnowledgeKind;
import tf.dpi.ai.support.FakeEmbeddingModel;
import tf.dpi.ai.support.TestDatabase;

/** Exercises pgvector SQL, Spring AI filter translation, knowledge versioning and the review gate on a real database. */
@EnabledIf(value = "tf.dpi.ai.support.TestDatabase#available", disabledReason = "no Docker and DPI_TEST_JDBC_URL unset")
class RetrievalIntegrationTest {

    static final EmbeddingProperties EMB = new EmbeddingProperties("bge-m3", "1", 1024, 8, Duration.ofSeconds(5));
    static JdbcTemplate jdbc;
    static DataSourceTransactionManager txm;
    static ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    static FakeEmbeddingModel model = new FakeEmbeddingModel(1024);
    static LogChunkRetrievalService logs;
    static KnowledgeRetrievalService kb;
    static KnowledgeIngestionService ingestion;

    @BeforeAll
    static void init() {
        DataSource ds = TestDatabase.migrated();
        jdbc = new JdbcTemplate(ds);
        txm = new DataSourceTransactionManager(ds);
        logs = new LogChunkRetrievalService(store("log_chunk_vectors"), EMB, new SimpleMeterRegistry());
        kb = new KnowledgeRetrievalService(store("knowledge_vectors"), EMB, new SimpleMeterRegistry());
        ingestion = new KnowledgeIngestionService(jdbc, txm, json, new MaskingEngine(MaskingPolicy.defaults()),
                texts -> texts.stream().map(model::embedText).toList(), EMB, new KnowledgeChunker(1500));
    }

    private static PgVectorStore store(String table) {
        return PgVectorStore.builder(jdbc, model).vectorTableName(table).dimensions(1024)
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE).indexType(PgVectorStore.PgIndexType.HNSW)
                .initializeSchema(false).build();
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM log_chunk_vectors");
        jdbc.update("DELETE FROM knowledge_vectors");
        jdbc.update("DELETE FROM knowledge_document");
    }

    private static void insertChunk(String id, String text, String service, String level, long from, long to,
            String embeddingModel) {
        String meta;
        try {
            meta = json.writeValueAsString(Map.of("chunkId", id, "service", service, "level", level, "fromEpochMs", from,
                    "toEpochMs", to, "embeddingModel", embeddingModel, "embeddingVersion", "1",
                    "sourceIds", List.of("d-" + id)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        jdbc.update("INSERT INTO log_chunk_vectors (id, content, metadata, embedding) VALUES (?, ?, ?::jsonb, ?::vector)",
                JdbcIngestionStore.rowId(id), text, meta, JdbcIngestionStore.vectorLiteral(model.embedText(text)));
    }

    private static Instant t(long ms) {
        return Instant.ofEpochMilli(ms);
    }

    @Test
    void filtersByServiceLevelTimeAndEmbeddingModel() {
        insertChunk("a", "connection refused to database host", "partner-service", "ERROR", 1000, 2000, "bge-m3");
        insertChunk("b", "connection refused to database host", "other-service", "ERROR", 1000, 2000, "bge-m3");
        insertChunk("c", "connection refused to database host", "partner-service", "INFO", 1000, 2000, "bge-m3");
        insertChunk("d", "connection refused to database host", "partner-service", "ERROR", 9000, 9500, "bge-m3");
        insertChunk("e", "connection refused to database host", "partner-service", "ERROR", 1000, 2000, "other-model");

        List<Evidence> r = logs.search("connection refused database",
                new SearchFilters("partner-service", LogLevel.ERROR, t(1500), t(3000), null, null, null), 10, 0.1);

        assertThat(r).extracting(Evidence::sourceRef).containsExactly("a");
        assertThat(r.get(0).isCurrent()).isTrue();
        assertThat(r.get(0).sourceIds()).containsExactly("d-a");
        assertThat(r.get(0).score()).isGreaterThan(0.5);
    }

    @Test
    void similarityThresholdExcludesUnrelatedText() {
        insertChunk("a", "connection refused to database host", "svc", "ERROR", 1, 2, "bge-m3");
        insertChunk("z", "zebra quantum pineapple harmonica", "svc", "ERROR", 1, 2, "bge-m3");
        List<Evidence> r = logs.search("connection refused database", SearchFilters.none(), 10, 0.5);
        assertThat(r).extracting(Evidence::sourceRef).containsExactly("a");
    }

    @Test
    void knowledgeIsVersionedMaskedAndOnlyActiveVersionIsRetrieved() {
        var v1 = ingestion.ingest("db-runbook", KnowledgeKind.RUNBOOK, "DB runbook", "wiki/db",
                "Restart the postgres connection pool. Contact admin@example.com for access.");
        assertThat(v1.version()).isEqualTo(1);
        assertThat(v1.validationStatus()).isEqualTo("UNVERIFIED");
        assertThat(jdbc.queryForObject("SELECT string_agg(content, ' ') FROM knowledge_vectors", String.class))
                .doesNotContain("admin@example.com");

        var same = ingestion.ingest("db-runbook", KnowledgeKind.RUNBOOK, "DB runbook", "wiki/db",
                "Restart the postgres connection pool. Contact admin@example.com for access.");
        assertThat(same.unchanged()).isTrue();
        assertThat(same.version()).isEqualTo(1);

        var v2 = ingestion.ingest("db-runbook", KnowledgeKind.RUNBOOK, "DB runbook", "wiki/db",
                "Check the postgres listener, then restart the connection pool.");
        assertThat(v2.version()).isEqualTo(2);

        List<Evidence> hits = kb.search("postgres connection pool", null, 10, 0.1);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).sourceRef()).contains("@v2");
        assertThat(hits.get(0).validationStatus()).isEqualTo("UNVERIFIED");
        assertThat(hits.get(0).isCurrent()).isFalse();

        assertThat(ingestion.validate("db-runbook", 2, "engineer1")).isTrue();
        assertThat(kb.search("postgres connection pool", null, 10, 0.1).get(0).validationStatus())
                .isEqualTo("ENGINEER_VALIDATED");
        assertThat(ingestion.validate("db-runbook", 9, "engineer1")).isFalse();
    }

    @Test
    void reviewDecisionIsRecordedExactlyOnce() {
        AnalysisRepository repo = new AnalysisRepository(jdbc, txm, json);
        UUID id = UUID.randomUUID();
        repo.create(id, "why failing", "alice", "cid-12345678");
        IncidentReport report = new IncidentReport(id.toString(), "q", Instant.parse("2026-11-06T10:00:00Z"),
                IncidentReport.Outcome.FINDINGS_VERIFIED, "s", List.of("svc"), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), "PENDING_ENGINEER_REVIEW");
        repo.completeWithReport(id, AnalysisStatus.AWAITING_REVIEW, report, "agent", "cid-12345678");

        assertThat(repo.findReport(id).orElseThrow().reviewStatus()).isEqualTo("PENDING_REVIEW");
        assertThat(repo.review(id, ReviewDecision.APPROVED, "bob", "looks right", "cid-12345678")).isTrue();
        assertThat(repo.review(id, ReviewDecision.REJECTED, "carol", "no", "cid-12345678")).isFalse();
        assertThat(repo.find(id).orElseThrow().status()).isEqualTo(AnalysisStatus.COMPLETED);
        assertThat(repo.findReport(id).orElseThrow().reviewedBy()).isEqualTo("bob");
        assertThat(jdbc.queryForList("SELECT action FROM audit_log WHERE subject = ? ORDER BY id", String.class,
                id.toString())).containsExactly("ANALYSIS_SUBMITTED", "REPORT_GENERATED", "REPORT_REVIEWED");
    }

    @Test
    void staleInFlightAnalysesAreFailedButFreshOnesAreLeftAlone() {
        AnalysisRepository repo = new AnalysisRepository(jdbc, txm, json);
        UUID stale = UUID.randomUUID();
        UUID fresh = UUID.randomUUID();
        repo.create(stale, "q", "u", "cid-12345678");
        repo.create(fresh, "q", "u", "cid-12345678");
        repo.updateStatus(stale, AnalysisStatus.RUNNING, null);
        jdbc.update("UPDATE analysis_request SET updated_at = now() - interval '1 hour' WHERE analysis_id = ?", stale);

        assertThat(repo.failStale(Duration.ofMinutes(10))).isEqualTo(1);
        assertThat(repo.find(stale).orElseThrow().status()).isEqualTo(AnalysisStatus.FAILED);
        assertThat(repo.find(stale).orElseThrow().failureReason()).isEqualTo("INTERRUPTED");
        assertThat(repo.find(fresh).orElseThrow().status()).isEqualTo(AnalysisStatus.QUEUED);
    }

    @Test
    void stepTrailIsStoredInOrderAndReplacedOnRewrite() {
        AnalysisRepository repo = new AnalysisRepository(jdbc, txm, json);
        UUID id = UUID.randomUUID();
        repo.create(id, "q", "u", "cid-12345678");
        repo.saveTrail(id, List.of("plan", "retrieval attempt 1", "report generated: FAILED"));
        repo.saveTrail(id, List.of("plan", "report generated: FAILED"));
        assertThat(repo.trail(id)).containsExactly("plan", "report generated: FAILED");
    }
}
