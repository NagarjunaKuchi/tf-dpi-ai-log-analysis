package tf.dpi.ai.agent.tools;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import tf.dpi.ai.agent.AgentProperties;
import tf.dpi.ai.elasticsearch.LogPage;
import tf.dpi.ai.elasticsearch.LogSource;
import tf.dpi.ai.elasticsearch.ReadRequest;
import tf.dpi.ai.evidence.Evidence;
import tf.dpi.ai.masking.EventSanitizer;
import tf.dpi.ai.model.LogEvent;
import tf.dpi.ai.model.LogLevel;
import tf.dpi.ai.vectorstore.KnowledgeRetrievalService;
import tf.dpi.ai.vectorstore.LogChunkRetrievalService;
import tf.dpi.ai.vectorstore.SearchFilters;

/**
 * The complete, fixed tool set available to the agent: vectorSearch, runbookSearch, esQuery. The agent graph invokes these
 * methods directly; the LLM never chooses or parameterises a tool call, so there is no model-driven tool injection
 * surface. The Elasticsearch tool is read-only (the {@link LogSource} port has no write operations), restricted to an
 * allowlist of services, a bounded time window and a bounded result size, and its output is masked before use.
 */
public class AgentTools {

    private static final Pattern SERVICE_NAME = Pattern.compile("^[A-Za-z0-9_.\\-]{1,64}$");
    private static final Pattern TRACE_OR_CODE = Pattern.compile("^[A-Za-z0-9_.\\-:]{1,128}$");

    private final LogChunkRetrievalService logs;
    private final KnowledgeRetrievalService knowledge;
    private final LogSource source;
    private final EventSanitizer sanitizer;
    private final AgentProperties props;

    public AgentTools(LogChunkRetrievalService logs, KnowledgeRetrievalService knowledge, LogSource source,
            EventSanitizer sanitizer, AgentProperties props) {
        this.logs = logs;
        this.knowledge = knowledge;
        this.source = source;
        this.sanitizer = sanitizer;
        this.props = props;
    }

    public List<Evidence> vectorSearch(String query, SearchFilters filters) {
        return logs.search(query, filters, props.topK(), props.similarityThreshold());
    }

    public List<Evidence> runbookSearch(String query) {
        return knowledge.search(query, null, props.knowledgeTopK(), props.similarityThreshold());
    }

    /** @throws IllegalArgumentException when the request violates the allowlist or bounds */
    public List<Evidence> esQuery(String service, Instant from, Instant to, LogLevel minLevel, String errorCode,
            String traceId) {
        if (service == null || !SERVICE_NAME.matcher(service).matches()) {
            throw new IllegalArgumentException("esQuery requires a valid service name");
        }
        Set<String> allowed = props.allowedServices();
        if (!allowed.isEmpty() && !allowed.contains(service)) {
            throw new IllegalArgumentException("service is not on the esQuery allowlist");
        }
        if (from == null || to == null || !from.isBefore(to)) {
            throw new IllegalArgumentException("esQuery requires from < to");
        }
        if (Duration.between(from, to).compareTo(props.maxLiveWindow()) > 0) {
            throw new IllegalArgumentException("esQuery window exceeds " + props.maxLiveWindow());
        }
        for (String s : new String[] {errorCode, traceId}) {
            if (s != null && !s.isBlank() && !TRACE_OR_CODE.matcher(s).matches()) {
                throw new IllegalArgumentException("esQuery identifier has invalid characters");
            }
        }
        LogPage page = source.readPage(new ReadRequest(from, List.of(), to, minLevel == null ? LogLevel.WARN : minLevel,
                Set.of(service), Set.of(), blankToNull(traceId), blankToNull(errorCode), props.maxLiveEvents()));
        List<Evidence> out = new ArrayList<>();
        for (LogEvent raw : page.events()) {
            LogEvent e = sanitizer.sanitize(raw); // throws MaskingException (fail closed) if it cannot be made safe
            String text = e.message() + (e.stackTrace() == null || e.stackTrace().isBlank() ? "" : "\n" + e.stackTrace());
            out.add(new Evidence(Evidence.idFor(Evidence.SourceSystem.ELASTICSEARCH_LIVE, e.id()),
                    Evidence.SourceSystem.ELASTICSEARCH_LIVE, Evidence.Nature.CURRENT_OBSERVATION, e.service(),
                    e.timestamp(), e.level(), text, e.id(), List.of(e.id()), 1.0, null));
        }
        return out;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
