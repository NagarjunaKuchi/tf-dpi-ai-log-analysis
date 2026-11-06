package tf.dpi.ai.agent;

import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Agent limits. Every loop in the graph is bounded by these values; the wall-clock timeout covers the whole run.
 *
 * @param allowedServices services the live Elasticsearch tool may query; empty means any syntactically valid name
 */
@ConfigurationProperties("dpi.agent")
public record AgentProperties(
        @DefaultValue("2") int maxRetrievalRetries,
        @DefaultValue("2") int maxVerificationRetries,
        @DefaultValue("120s") Duration timeout,
        @DefaultValue("8") int topK,
        @DefaultValue("0.5") double similarityThreshold,
        @DefaultValue("0.6") double gradeMinScore,
        @DefaultValue("3") int knowledgeTopK,
        @DefaultValue("1h") Duration maxLiveWindow,
        @DefaultValue("50") int maxLiveEvents,
        @DefaultValue("") Set<String> allowedServices) {

    public AgentProperties {
        if (maxRetrievalRetries < 0 || maxRetrievalRetries > 5 || maxVerificationRetries < 0 || maxVerificationRetries > 5) {
            throw new IllegalArgumentException("dpi.agent retry limits must be within 0..5");
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("dpi.agent.timeout must be positive");
        }
        if (topK < 1 || knowledgeTopK < 1 || maxLiveEvents < 1) {
            throw new IllegalArgumentException("dpi.agent topK / knowledgeTopK / maxLiveEvents must be positive");
        }
        allowedServices = allowedServices == null ? Set.of() : Set.copyOf(allowedServices);
    }
}
