package tf.dpi.ai.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static tf.dpi.ai.agent.graph.AgentStateKeys.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.bsc.langgraph4j.CompiledGraph;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import tf.dpi.ai.agent.AgentLlm;
import tf.dpi.ai.agent.AgentProperties;
import tf.dpi.ai.agent.Scenario;
import tf.dpi.ai.agent.dto.Confidence;
import tf.dpi.ai.agent.dto.Finding;
import tf.dpi.ai.agent.dto.QueryAnalysis;
import tf.dpi.ai.agent.dto.RcaResult;
import tf.dpi.ai.agent.graph.AnalysisGraphFactory;
import tf.dpi.ai.agent.graph.DpiAgentState;
import tf.dpi.ai.agent.nodes.AgentNodes;
import tf.dpi.ai.agent.tools.AgentTools;
import tf.dpi.ai.elasticsearch.LogPage;
import tf.dpi.ai.elasticsearch.LogSource;
import tf.dpi.ai.embedding.EmbeddingProperties;
import tf.dpi.ai.evidence.Evidence;
import tf.dpi.ai.evidence.EvidenceVerifier;
import tf.dpi.ai.ingestion.JdbcIngestionStore;
import tf.dpi.ai.masking.EventSanitizer;
import tf.dpi.ai.masking.MaskingEngine;
import tf.dpi.ai.masking.MaskingPolicy;
import tf.dpi.ai.masking.StructuredLogMasker;
import tf.dpi.ai.report.IncidentReport;
import tf.dpi.ai.report.IncidentReport.Outcome;
import tf.dpi.ai.runbook.KnowledgeChunker;
import tf.dpi.ai.runbook.KnowledgeIngestionService;
import tf.dpi.ai.runbook.KnowledgeKind;
import tf.dpi.ai.support.FakeEmbeddingModel;
import tf.dpi.ai.support.TestDatabase;
import tf.dpi.ai.vectorstore.KnowledgeRetrievalService;
import tf.dpi.ai.vectorstore.LogChunkRetrievalService;

/**
 * Real graph + real tools + real pgvector retrieval; only the LLM is scripted (deterministic) and Elasticsearch is empty.
 * Proves the end-to-end contract: supported findings pass, unsupported ones are rejected, nothing is auto-remediated.
 */
@EnabledIf(value = "tf.dpi.ai.support.TestDatabase#available", disabledReason = "no Docker and DPI_TEST_JDBC_URL unset")
class AgentEndToEndIT {

    static final Instant NOW = Instant.parse("2026-11-06T10:00:00Z");
    static final EmbeddingProperties EMB = new EmbeddingProperties("bge-m3", "1", 1024, 8, Duration.ofSeconds(5));
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    static final FakeEmbeddingModel MODEL = new FakeEmbeddingModel(1024);
    static JdbcTemplate jdbc;
    static AgentTools tools;
    static MaskingEngine masking = new MaskingEngine(MaskingPolicy.defaults());

    @BeforeAll
    static void init() {
        var ds = TestDatabase.migrated();
        jdbc = new JdbcTemplate(ds);
        var txm = new DataSourceTransactionManager(ds);
        var meters = new SimpleMeterRegistry();
        var logs = new LogChunkRetrievalService(store("log_chunk_vectors"), EMB, meters);
        var kb = new KnowledgeRetrievalService(store("knowledge_vectors"), EMB, meters);
        LogSource empty = mock(LogSource.class);
        when(empty.readPage(org.mockito.ArgumentMatchers.any())).thenReturn(new LogPage(List.of(), List.of(), List.of(), true));
        tools = new AgentTools(logs, kb, empty, new EventSanitizer(masking, new StructuredLogMasker(masking, JSON)),
                new AgentProperties(2, 2, Duration.ofSeconds(60), 8, 0.2, 0.2, 3, Duration.ofHours(1), 50, Set.of()));
        var ingestion = new KnowledgeIngestionService(jdbc, txm, JSON, masking,
                t -> t.stream().map(MODEL::embedText).toList(), EMB, new KnowledgeChunker(1500));
        ingestion.ingest("db-runbook", KnowledgeKind.RUNBOOK, "Database connectivity", "wiki/db",
                "When the database refuses connections check the postgres listener, firewall and connection pool size.");
        ingestion.ingest("mq-runbook", KnowledgeKind.RUNBOOK, "ActiveMQ broker", "wiki/mq",
                "When ActiveMQ clients cannot connect check the broker is running and the failover transport URL.");
    }

    private static PgVectorStore store(String table) {
        return PgVectorStore.builder(jdbc, MODEL).vectorTableName(table).dimensions(1024)
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE).indexType(PgVectorStore.PgIndexType.HNSW)
                .initializeSchema(false).build();
    }

    @BeforeEach
    void cleanLogs() {
        jdbc.update("DELETE FROM log_chunk_vectors");
    }

    private static void logChunk(String id, String text) {
        try {
            String meta = JSON.writeValueAsString(Map.of("chunkId", id, "service", "partner-service", "level", "ERROR",
                    "fromEpochMs", NOW.minusSeconds(120).toEpochMilli(), "toEpochMs", NOW.minusSeconds(60).toEpochMilli(),
                    "embeddingModel", "bge-m3", "embeddingVersion", "1", "sourceIds", List.of("es-" + id)));
            jdbc.update("INSERT INTO log_chunk_vectors (id, content, metadata, embedding) VALUES (?, ?, ?::jsonb, ?::vector)",
                    JdbcIngestionStore.rowId(id), text, meta, JdbcIngestionStore.vectorLiteral(MODEL.embedText(text)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Deterministic stand-in for the LLM: cites the first scenario signature it sees, quoting real evidence text. */
    static class ScriptedLlm implements AgentLlm {
        final boolean hallucinate;

        ScriptedLlm(boolean hallucinate) {
            this.hallucinate = hallucinate;
        }

        @Override
        public QueryAnalysis analyze(String q) {
            return new QueryAnalysis(null, null, null, null, null, null, List.of("failing", "error"));
        }

        @Override
        public String rewrite(String q, String prev, int attempt) {
            return prev;
        }

        @Override
        public RcaResult analyzeRootCause(String q, List<Evidence> current, List<Evidence> kb, List<Scenario> sc, List<String> rej) {
            if (current.isEmpty()) {
                return new RcaResult("", List.of(), List.of(), List.of("no evidence"));
            }
            Evidence e = current.get(0);
            if (hallucinate) { // claims an OOM that the logs do not contain, "supported" by an unrelated log item
                return new RcaResult("Heap exhausted", List.of(new Finding("JVM ran out of heap memory",
                        "OUT_OF_MEMORY", List.of(e.id()), List.of("OutOfMemoryError"), Confidence.HIGH)), List.of(), List.of());
            }
            Scenario s = sc.get(0);
            String quote = switch (s) {
                case DB_CONNECTION_REFUSED -> "Connection refused";
                case OUT_OF_MEMORY -> "OutOfMemoryError";
                case ACTIVEMQ_ISSUE -> "ActiveMQ";
            };
            List<String> ids = current.stream().filter(x -> x.excerpt().contains(quote)).map(Evidence::id).toList();
            return new RcaResult("Likely " + s, List.of(new Finding("Evidence shows " + s, s.name(), ids, List.of(quote),
                    Confidence.HIGH)), List.of("Manually confirm " + s + " with the owning team"), List.of());
        }
    }

    private IncidentReport run(AgentLlm llm, String question) throws Exception {
        AgentProperties props = new AgentProperties(2, 2, Duration.ofSeconds(60), 8, 0.2, 0.2, 3, Duration.ofHours(1), 50,
                Set.of());
        AgentNodes nodes = new AgentNodes(llm, tools, new EvidenceVerifier(), masking, props,
                Clock.fixed(NOW, ZoneOffset.UTC));
        CompiledGraph<DpiAgentState> g = AnalysisGraphFactory.build(nodes, null);
        return g.invoke(Map.of(ANALYSIS_ID, "A", QUESTION, question, STARTED_AT, NOW)).orElseThrow().report();
    }

    @Test
    void dbConnectionRefusedIsVerifiedWithRunbookAsSupportingKnowledgeOnly() throws Exception {
        logChunk("c1", "ERROR partner-service failing: Caused by: java.net.ConnectException: Connection refused to db-host:5432");
        logChunk("c2", "WARN HikariPool-1 - Connection is not available, request timed out; database error failing");

        IncidentReport r = run(new ScriptedLlm(false), "database refuses connections, postgres connection pool, partner-service failing error");

        assertThat(r.outcome()).isEqualTo(Outcome.FINDINGS_VERIFIED);
        assertThat(r.verifiedFindings()).singleElement().satisfies(f -> assertThat(f.category()).isEqualTo("DB_CONNECTION_REFUSED"));
        assertThat(r.supportingEvidence()).allMatch(Evidence::isCurrent);
        assertThat(r.relatedKnowledge()).anyMatch(k -> k.sourceRef().contains("db-runbook"));
        assertThat(r.relatedKnowledge()).allMatch(k -> !k.isCurrent() && "UNVERIFIED".equals(k.validationStatus()));
        assertThat(r.reviewStatus()).isEqualTo("PENDING_ENGINEER_REVIEW");
        assertThat(r.suggestedActions()).allMatch(a -> a.startsWith("Manually"));
    }

    @Test
    void outOfMemoryScenarioIsVerified() throws Exception {
        logChunk("o1", "ERROR partner-service failing error: java.lang.OutOfMemoryError: Java heap space at com.x.Cache.load");
        IncidentReport r = run(new ScriptedLlm(false), "partner-service failing with error, what happened?");
        assertThat(r.outcome()).isEqualTo(Outcome.FINDINGS_VERIFIED);
        assertThat(r.verifiedFindings().get(0).category()).isEqualTo("OUT_OF_MEMORY");
    }

    @Test
    void activeMqScenarioIsVerifiedAndMqRunbookIsRelated() throws Exception {
        logChunk("m1", "ERROR partner-service failing error: javax.jms.JMSException: Could not connect to ActiveMQ broker failover:(tcp://mq:61616)");
        IncidentReport r = run(new ScriptedLlm(false), "partner-service failing, ActiveMQ error broker failover");
        assertThat(r.outcome()).isEqualTo(Outcome.FINDINGS_VERIFIED);
        assertThat(r.verifiedFindings().get(0).category()).isEqualTo("ACTIVEMQ_ISSUE");
        assertThat(r.relatedKnowledge()).anyMatch(k -> k.sourceRef().contains("mq-runbook"));
    }

    @Test
    void unsupportedFindingIsRejectedAndNeverReachesTheReport() throws Exception {
        logChunk("c1", "ERROR partner-service failing: Caused by: java.net.ConnectException: Connection refused to db-host:5432");

        IncidentReport r = run(new ScriptedLlm(true), "Why is partner-service failing with error?");

        assertThat(r.outcome()).isEqualTo(Outcome.INSUFFICIENT_EVIDENCE);
        assertThat(r.verifiedFindings()).isEmpty();
        assertThat(r.rejectedClaims()).isNotEmpty();
        assertThat(r.rejectedClaims().get(0).reason()).contains("quoted term not found");
        assertThat(r.summary()).doesNotContain("Heap exhausted");
    }

    @Test
    void sensitiveValuesInQuestionAndEvidenceNeverReachTheReport() throws Exception {
        logChunk("p1", "ERROR partner-service failing error: Connection refused for user token=abc123secretvalue");
        IncidentReport r = run(new ScriptedLlm(false), "partner-service failing error for bob@example.com");
        String all = JSON.writeValueAsString(r);
        assertThat(all).doesNotContain("bob@example.com");
    }

    @Test
    void noEvidenceYieldsInsufficientEvidenceNotAGuess() throws Exception {
        IncidentReport r = run(new ScriptedLlm(false), "Why is partner-service failing?");
        assertThat(r.outcome()).isEqualTo(Outcome.INSUFFICIENT_EVIDENCE);
        assertThat(r.verifiedFindings()).isEmpty();
    }
}
