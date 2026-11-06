package tf.dpi.ai.agent.graph;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

import java.util.Map;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import tf.dpi.ai.agent.nodes.AgentNodes;

/**
 * Builds the LangGraph4j graph. Flow:
 * <pre>
 * START → queryAnalyzer → planner → retrieval → relevanceGrader ─┬─(relevant)→ rootCause → evidenceVerification ─┬→ reportGeneration
 *                                      ▲                         ├─(none, retries left)→ queryRewrite ─┐        └─(retry)→ rootCause
 *                                      └─────────────────────────┴────────────────────────────────────┘
 * reportGeneration → engineerReview → END
 * </pre>
 * Both loops are bounded by {@code dpi.agent.max-*-retries}; any node failure or timeout routes to reportGeneration.
 */
public final class AnalysisGraphFactory {

    private AnalysisGraphFactory() {
    }

    public static CompiledGraph<DpiAgentState> build(AgentNodes n, BaseCheckpointSaver saver) {
        try {
            StateGraph<DpiAgentState> g = new StateGraph<>(DpiAgentState.SCHEMA, DpiAgentState::new);
            g.addNode("queryAnalyzer", node_async(n::queryAnalyzer));
            g.addNode("planner", node_async(n::planner));
            g.addNode("retrieval", node_async(n::retrieval));
            g.addNode("relevanceGrader", node_async(n::relevanceGrader));
            g.addNode("queryRewrite", node_async(n::queryRewrite));
            g.addNode("rootCause", node_async(n::rootCause));
            g.addNode("evidenceVerification", node_async(n::evidenceVerification));
            g.addNode("reportGeneration", node_async(n::reportGeneration));
            g.addNode("engineerReview", node_async(n::engineerReview));

            g.addEdge(START, "queryAnalyzer");
            g.addConditionalEdges("queryAnalyzer", edge_async(n::afterStep),
                    Map.of("next", "planner", "report", "reportGeneration"));
            g.addEdge("planner", "retrieval");
            g.addConditionalEdges("retrieval", edge_async(n::afterStep),
                    Map.of("next", "relevanceGrader", "report", "reportGeneration"));
            g.addConditionalEdges("relevanceGrader", edge_async(n::afterGrading),
                    Map.of("analyse", "rootCause", "rewrite", "queryRewrite", "report", "reportGeneration"));
            g.addConditionalEdges("queryRewrite", edge_async(n::afterStep),
                    Map.of("next", "retrieval", "report", "reportGeneration"));
            g.addConditionalEdges("rootCause", edge_async(n::afterStep),
                    Map.of("next", "evidenceVerification", "report", "reportGeneration"));
            g.addConditionalEdges("evidenceVerification", edge_async(n::afterVerification),
                    Map.of("retry", "rootCause", "report", "reportGeneration"));
            g.addEdge("reportGeneration", "engineerReview");
            g.addEdge("engineerReview", END);

            CompileConfig.Builder cfg = CompileConfig.builder();
            if (saver != null) {
                cfg.checkpointSaver(saver);
            }
            return g.compile(cfg.build());
        } catch (GraphStateException e) {
            throw new IllegalStateException("Invalid analysis graph definition", e);
        }
    }
}
