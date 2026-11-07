package tf.dpi.ai.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import java.util.function.Supplier;
import tf.dpi.ai.agent.AgentLlm;
import tf.dpi.ai.agent.dto.QueryAnalysis;
import tf.dpi.ai.agent.dto.RcaResult;
import tf.dpi.ai.evidence.Evidence;

/** Decorator recording latency and success/failure of every LLM call (dpi.llm.call). Labels never contain content. */
public class MeteredAgentLlm implements AgentLlm {

    private final AgentLlm delegate;
    private final MeterRegistry meters;

    public MeteredAgentLlm(AgentLlm delegate, MeterRegistry meters) {
        this.delegate = delegate;
        this.meters = meters;
    }

    @Override
    public QueryAnalysis analyze(String question) {
        return timed("analyze", () -> delegate.analyze(question));
    }

    @Override
    public String rewrite(String question, String previousQuery, int attempt) {
        return timed("rewrite", () -> delegate.rewrite(question, previousQuery, attempt));
    }

    @Override
    public RcaResult analyzeRootCause(String question, List<Evidence> current, List<Evidence> knowledge,
            List<String> detectedSignatures, List<String> priorRejections) {
        return timed("rootCause", () -> delegate.analyzeRootCause(question, current, knowledge, detectedSignatures,
                priorRejections));
    }

    private <T> T timed(String operation, Supplier<T> call) {
        Timer.Sample sample = Timer.start(meters);
        String result = "error";
        try {
            T out = call.get();
            result = "ok";
            return out;
        } finally {
            sample.stop(Timer.builder("dpi.llm.call").tag("operation", operation).tag("result", result).register(meters));
        }
    }
}
