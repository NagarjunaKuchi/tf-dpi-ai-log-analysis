package tf.dpi.ai.agent;

import java.util.List;
import tf.dpi.ai.agent.dto.QueryAnalysis;
import tf.dpi.ai.agent.dto.RcaResult;
import tf.dpi.ai.evidence.Evidence;

/** The only place the agent talks to the self-hosted LLM. Inputs are already masked. Implementations must not retry. */
public interface AgentLlm {

    QueryAnalysis analyze(String question);

    /** Returns an alternative search phrasing, or the original when no better one exists. */
    String rewrite(String question, String previousQuery, int attempt);

    RcaResult analyzeRootCause(String question, List<Evidence> current, List<Evidence> knowledge,
            List<String> detectedSignatures, List<String> priorRejections);
}
