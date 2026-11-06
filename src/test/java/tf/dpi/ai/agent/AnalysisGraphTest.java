package tf.dpi.ai.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static tf.dpi.ai.agent.graph.AgentStateKeys.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.bsc.langgraph4j.CompiledGraph;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tf.dpi.ai.agent.dto.Confidence;
import tf.dpi.ai.agent.dto.Finding;
import tf.dpi.ai.agent.dto.QueryAnalysis;
import tf.dpi.ai.agent.dto.RcaResult;
import tf.dpi.ai.agent.graph.AnalysisGraphFactory;
import tf.dpi.ai.agent.graph.DpiAgentState;
import tf.dpi.ai.agent.nodes.AgentNodes;
import tf.dpi.ai.agent.tools.AgentTools;
import tf.dpi.ai.evidence.Evidence;
import tf.dpi.ai.evidence.Evidence.Nature;
import tf.dpi.ai.evidence.Evidence.SourceSystem;
import tf.dpi.ai.evidence.EvidenceVerifier;
import tf.dpi.ai.masking.MaskingEngine;
import tf.dpi.ai.masking.MaskingPolicy;
import tf.dpi.ai.model.LogLevel;
import tf.dpi.ai.report.IncidentReport;
import tf.dpi.ai.report.IncidentReport.Outcome;
import tf.dpi.ai.vectorstore.SearchFilters;

class AnalysisGraphTest {

    private static final Instant NOW = Instant.parse("2026-11-06T10:00:00Z");

    private final AgentLlm llm = mock(AgentLlm.class);
    private final AgentTools tools = mock(AgentTools.class);
    private CompiledGraph<DpiAgentState> graph;

    private final Evidence dbLog = log("chunk-1", "Caused by: java.net.ConnectException: Connection refused to db-host:5432");
    private final Evidence runbook = new Evidence("E-rb", SourceSystem.RUNBOOK_KB, Nature.HISTORICAL_KNOWLEDGE, "DB runbook",
            null, null, "Check that PostgreSQL accepts connections and the pool is sized correctly.", "rb#0", List.of(), 0.9,
            "UNVERIFIED");

    @BeforeEach
    void setUp() {
        AgentProperties props = new AgentProperties(2, 2, java.time.Duration.ofSeconds(60), 8, 0.5, 0.6, 3,
                java.time.Duration.ofHours(1), 50, java.util.Set.of());
        AgentNodes nodes = new AgentNodes(llm, tools, new EvidenceVerifier(), new MaskingEngine(MaskingPolicy.defaults()),
                props, Clock.fixed(NOW, ZoneOffset.UTC));
        graph = AnalysisGraphFactory.build(nodes, null);
        when(llm.analyze(any())).thenReturn(new QueryAnalysis(null, null, null, null, null, null, List.of("refused")));
        when(llm.rewrite(any(), any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn("rewritten");
        when(tools.runbookSearch(any())).thenReturn(List.of(runbook));
    }

    private static Evidence log(String ref, String text) {
        return new Evidence(Evidence.idFor(SourceSystem.PGVECTOR_LOG_CHUNKS, ref), SourceSystem.PGVECTOR_LOG_CHUNKS,
                Nature.CURRENT_OBSERVATION, "partner-service", NOW.minusSeconds(60), LogLevel.ERROR, text, ref,
                List.of("doc-1"), 0.9, null);
    }

    private IncidentReport run() throws Exception {
        DpiAgentState end = graph.invoke(Map.of(ANALYSIS_ID, "A-1", QUESTION, "Why is partner-service failing?",
                STARTED_AT, NOW)).orElseThrow();
        return end.report();
    }

    @Test
    void verifiedFindingProducesReportPendingEngineerReview() throws Exception {
        when(tools.vectorSearch(any(), any(SearchFilters.class))).thenReturn(List.of(dbLog));
        Finding good = new Finding("Database refused connections", "DB_CONNECTION_REFUSED", List.of(dbLog.id()),
                List.of("Connection refused"), Confidence.HIGH);
        when(llm.analyzeRootCause(any(), any(), any(), any(), any()))
                .thenReturn(new RcaResult("DB down", List.of(good), List.of("Check DB listener"), List.of()));

        IncidentReport r = run();

        assertThat(r.outcome()).isEqualTo(Outcome.FINDINGS_VERIFIED);
        assertThat(r.verifiedFindings()).hasSize(1);
        assertThat(r.verifiedFindings().get(0).confidence()).isEqualTo(Confidence.MEDIUM); // only one current item
        assertThat(r.supportingEvidence()).containsExactly(dbLog);
        assertThat(r.reviewStatus()).isEqualTo(AgentNodes.PENDING_REVIEW);
        assertThat(r.relatedKnowledge()).containsExactly(runbook);
        assertThat(r.limitations()).anyMatch(l -> l.contains("UNVERIFIED"));
    }

    @Test
    void unsupportedFindingIsRejectedAndRetriesAreBounded() throws Exception {
        when(tools.vectorSearch(any(), any(SearchFilters.class))).thenReturn(List.of(dbLog));
        Finding invented = new Finding("Out of memory on host", "OUT_OF_MEMORY", List.of(dbLog.id()),
                List.of("OutOfMemoryError"), Confidence.HIGH);
        when(llm.analyzeRootCause(any(), any(), any(), any(), any()))
                .thenReturn(new RcaResult("OOM", List.of(invented), List.of(), List.of()));

        IncidentReport r = run();

        assertThat(r.outcome()).isEqualTo(Outcome.INSUFFICIENT_EVIDENCE);
        assertThat(r.verifiedFindings()).isEmpty();
        assertThat(r.rejectedClaims()).isNotEmpty();
        // 1 initial + maxVerificationRetries(2) analyses, never more
        verify(llm, org.mockito.Mockito.times(3)).analyzeRootCause(any(), any(), any(), any(), any());
    }

    @Test
    void historicalKnowledgeAloneCannotProveACurrentFault() throws Exception {
        when(tools.vectorSearch(any(), any(SearchFilters.class))).thenReturn(List.of(dbLog));
        Finding fromRunbook = new Finding("Pool misconfigured", "OTHER", List.of(runbook.id()),
                List.of("pool is sized"), Confidence.MEDIUM);
        when(llm.analyzeRootCause(any(), any(), any(), any(), any()))
                .thenReturn(new RcaResult("x", List.of(fromRunbook), List.of(), List.of()));

        IncidentReport r = run();

        assertThat(r.outcome()).isEqualTo(Outcome.INSUFFICIENT_EVIDENCE);
        assertThat(r.rejectedClaims().get(0).reason()).contains("historical");
    }

    @Test
    void noRelevantEvidenceRewritesAtMostMaxTimesThenReportsInsufficient() throws Exception {
        when(tools.vectorSearch(any(), any(SearchFilters.class))).thenReturn(List.of());

        IncidentReport r = run();

        assertThat(r.outcome()).isEqualTo(Outcome.INSUFFICIENT_EVIDENCE);
        verify(llm, org.mockito.Mockito.times(2)).rewrite(any(), any(), org.mockito.ArgumentMatchers.anyInt());
        verify(tools, org.mockito.Mockito.times(3)).vectorSearch(any(), any(SearchFilters.class));
        verify(llm, never()).analyzeRootCause(any(), any(), any(), any(), any());
    }

    @Test
    void llmFailureEndsInExplicitFailedOutcomeWithoutLeakingMessage() throws Exception {
        when(llm.analyze(any())).thenThrow(new IllegalStateException("secret-token-123 leaked"));

        IncidentReport r = run();

        assertThat(r.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(r.limitations()).anyMatch(l -> l.contains("IllegalStateException"));
        assertThat(r.limitations()).noneMatch(l -> l.contains("secret-token-123"));
        assertThat(r.reviewStatus()).isEqualTo(AgentNodes.PENDING_REVIEW);
    }

    @Test
    void liveElasticsearchToolIsCalledOnlyWhenServiceAndWindowAreKnown() throws Exception {
        when(llm.analyze(any())).thenReturn(new QueryAnalysis("partner-service", LogLevel.ERROR, NOW.minusSeconds(600), NOW,
                null, null, List.of("refused")));
        when(tools.vectorSearch(any(), any(SearchFilters.class))).thenReturn(List.of(dbLog));
        when(tools.esQuery(any(), any(), any(), any(), any(), any())).thenReturn(List.of());
        when(llm.analyzeRootCause(any(), any(), any(), any(), any())).thenReturn(new RcaResult("", List.of(), List.of(), List.of()));

        run();

        verify(tools).esQuery(any(), any(), any(), any(), any(), any());
    }
}
