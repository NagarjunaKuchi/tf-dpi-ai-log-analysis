package tf.dpi.ai.agent.graph;

import static tf.dpi.ai.agent.graph.AgentStateKeys.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;
import tf.dpi.ai.agent.dto.QueryAnalysis;
import tf.dpi.ai.agent.dto.RcaResult;
import tf.dpi.ai.agent.dto.VerificationResult;
import tf.dpi.ai.evidence.Evidence;
import tf.dpi.ai.report.IncidentReport;

/** Typed view over the LangGraph4j state map. Only {@code trail} accumulates; every other key is last-write-wins. */
public class DpiAgentState extends AgentState {

    public static final Map<String, Channel<?>> SCHEMA = Map.of(TRAIL, Channels.appender(ArrayList::new));

    public DpiAgentState(Map<String, Object> init) {
        super(init);
    }

    public String analysisId() {
        return this.<String>value(ANALYSIS_ID).orElse("");
    }

    public String question() {
        return this.<String>value(QUESTION).orElse("");
    }

    public java.time.Instant startedAt() {
        return this.<java.time.Instant>value(STARTED_AT).orElseThrow();
    }

    public QueryAnalysis query() {
        return this.<QueryAnalysis>value(QUERY).orElse(null);
    }

    public String searchText() {
        return this.<String>value(SEARCH_TEXT).orElse(question());
    }

    public List<Evidence> evidence() {
        return this.<List<Evidence>>value(EVIDENCE).orElse(List.of());
    }

    public List<Evidence> relevant() {
        return this.<List<Evidence>>value(RELEVANT).orElse(List.of());
    }

    public List<Evidence> knowledge() {
        return this.<List<Evidence>>value(KNOWLEDGE).orElse(List.of());
    }

    public int retrievalAttempts() {
        return this.<Integer>value(RETRIEVAL_ATTEMPTS).orElse(0);
    }

    public int verificationAttempts() {
        return this.<Integer>value(VERIFICATION_ATTEMPTS).orElse(0);
    }

    public RcaResult rca() {
        return this.<RcaResult>value(RCA).orElse(null);
    }

    public VerificationResult verification() {
        return this.<VerificationResult>value(VERIFICATION).orElse(null);
    }

    public List<String> rejections() {
        return this.<List<String>>value(REJECTIONS).orElse(List.of());
    }

    public IncidentReport report() {
        return this.<IncidentReport>value(REPORT).orElse(null);
    }

    /** Content-free failure code (never contains log text); null while the run is healthy. */
    public String failure() {
        return this.<String>value(FAILURE).orElse(null);
    }
}
