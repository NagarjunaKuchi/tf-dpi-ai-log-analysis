package tf.dpi.ai.agent.dto;

import java.time.Instant;
import java.util.List;
import tf.dpi.ai.model.LogLevel;

/** Structured interpretation of the engineer's question. All fields except {@code keywords} are optional. */
public record QueryAnalysis(String service, LogLevel minLevel, Instant from, Instant to, String errorCode,
        String traceId, List<String> keywords) implements java.io.Serializable {

    public QueryAnalysis {
        keywords = keywords == null ? List.of() : keywords.stream().filter(k -> k != null && !k.isBlank()).toList();
    }
}
