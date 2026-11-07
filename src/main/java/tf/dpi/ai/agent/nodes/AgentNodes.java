package tf.dpi.ai.agent.nodes;

import static tf.dpi.ai.agent.graph.AgentStateKeys.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tf.dpi.ai.agent.AgentLlm;
import tf.dpi.ai.agent.AgentProperties;
import tf.dpi.ai.agent.dto.Finding;
import tf.dpi.ai.agent.dto.QueryAnalysis;
import tf.dpi.ai.agent.dto.RcaResult;
import tf.dpi.ai.agent.dto.VerificationResult;
import tf.dpi.ai.agent.graph.DpiAgentState;
import tf.dpi.ai.agent.tools.AgentTools;
import tf.dpi.ai.evidence.Evidence;
import tf.dpi.ai.evidence.EvidenceVerifier;
import tf.dpi.ai.masking.MaskingEngine;
import tf.dpi.ai.report.IncidentReport;
import tf.dpi.ai.report.IncidentReport.Outcome;
import tf.dpi.ai.vectorstore.SearchFilters;

/**
 * The nine graph nodes. Each returns a partial state update and never throws: any failure is converted to a content-free
 * failure code that the conditional edges route to report generation, so a run always ends in an explicit outcome.
 */
public class AgentNodes {

    private static final Logger log = LoggerFactory.getLogger(AgentNodes.class);
    public static final String PENDING_REVIEW = "PENDING_ENGINEER_REVIEW";

    private final AgentLlm llm;
    private final AgentTools tools;
    private final EvidenceVerifier verifier;
    private final MaskingEngine masking;
    private final AgentProperties props;
    private final Clock clock;

    public AgentNodes(AgentLlm llm, AgentTools tools, EvidenceVerifier verifier, MaskingEngine masking,
            AgentProperties props, Clock clock) {
        this.llm = llm;
        this.tools = tools;
        this.verifier = verifier;
        this.masking = masking;
        this.props = props;
        this.clock = clock;
    }

    // ---- 1. query analyzer ------------------------------------------------------------------------------------------
    public Map<String, Object> queryAnalyzer(DpiAgentState s) {
        return guarded("queryAnalyzer", s, () -> {
            QueryAnalysis qa = llm.analyze(s.question());
            return Map.of(QUERY, qa == null ? new QueryAnalysis(null, null, null, null, null, null, List.of()) : qa,
                    SEARCH_TEXT, s.question());
        });
    }

    // ---- 2. planner (deterministic: choose filters; widen on retries) -----------------------------------------------
    public Map<String, Object> planner(DpiAgentState s) {
        return guarded("planner", s, () -> Map.of(TRAIL, "plan: filtered semantic search, then knowledge lookup"));
    }

    // ---- 3. retrieval -----------------------------------------------------------------------------------------------
    public Map<String, Object> retrieval(DpiAgentState s) {
        return guarded("retrieval", s, () -> {
            QueryAnalysis q = s.query();
            int attempt = s.retrievalAttempts();
            SearchFilters f = new SearchFilters(q.service(), q.minLevel(), q.from(), q.to(), q.errorCode(), q.traceId(),
                    null);
            if (attempt > 0) {
                f = f.withoutNarrowing(); // widen: drop service-specific narrowing on retries, keep level/time
            }
            List<Evidence> found = new ArrayList<>(tools.vectorSearch(s.searchText(), f));
            // Live Elasticsearch confirmation is only possible when the question pinned a service and a bounded window.
            if (q.service() != null && q.from() != null && q.to() != null) {
                try {
                    found.addAll(tools.esQuery(q.service(), q.from(), q.to(), q.minLevel(), q.errorCode(), q.traceId()));
                } catch (IllegalArgumentException denied) {
                    log.info("esQuery skipped by tool policy for analysis {}: {}", s.analysisId(), denied.getMessage());
                }
            }
            List<Evidence> kb = tools.runbookSearch(s.searchText());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put(EVIDENCE, dedupe(found));
            out.put(KNOWLEDGE, dedupe(kb));
            out.put(RETRIEVAL_ATTEMPTS, attempt + 1);
            out.put(TRAIL, "retrieval attempt " + (attempt + 1) + ": " + found.size() + " log items, " + kb.size()
                    + " knowledge items");
            return out;
        });
    }

    // ---- 4. relevance grader (deterministic) --------------------------------------------------------------------
    public Map<String, Object> relevanceGrader(DpiAgentState s) {
        return guarded("relevanceGrader", s, () -> {
            List<String> kw = s.query().keywords().stream().map(k -> k.toLowerCase(Locale.ROOT)).toList();
            List<Evidence> keep = s.evidence().stream().filter(e -> {
                String t = e.excerpt().toLowerCase(Locale.ROOT);
                return e.score() >= props.gradeMinScore() || kw.stream().anyMatch(t::contains);
            }).sorted(Comparator.comparingDouble(Evidence::score).reversed()).toList();
            return Map.of(RELEVANT, keep, TRAIL, "graded: " + keep.size() + "/" + s.evidence().size() + " relevant");
        });
    }

    // ---- 5. query rewrite ---------------------------------------------------------------------------------------
    public Map<String, Object> queryRewrite(DpiAgentState s) {
        return guarded("queryRewrite", s, () -> {
            String rewritten = llm.rewrite(s.question(), s.searchText(), s.retrievalAttempts());
            return Map.of(SEARCH_TEXT, rewritten, TRAIL, "query rewritten");
        });
    }

    // ---- 6. root cause analysis ---------------------------------------------------------------------------------
    public Map<String, Object> rootCause(DpiAgentState s) {
        return guarded("rootCause", s, () -> {
            List<Evidence> current = s.relevant().stream().filter(Evidence::isCurrent).toList();
            String currentText = current.stream().map(Evidence::excerpt).collect(Collectors.joining("\n"));
            List<String> signatures = verifier.signatures().detect(currentText);
            RcaResult r = llm.analyzeRootCause(s.question(), current, s.knowledge(), signatures, s.rejections());
            return Map.of(RCA, r == null ? new RcaResult("", List.of(), List.of(), List.of()) : r,
                    VERIFICATION_ATTEMPTS, s.verificationAttempts() + 1,
                    TRAIL, "root cause analysis produced " + (r == null ? 0 : r.findings().size()) + " finding(s)");
        });
    }

    // ---- 7. evidence verification (deterministic) -------------------------------------------------------------
    public Map<String, Object> evidenceVerification(DpiAgentState s) {
        return guarded("evidenceVerification", s, () -> {
            List<Evidence> pool = new ArrayList<>(s.relevant());
            pool.addAll(s.knowledge());
            VerificationResult v = verifier.verify(s.rca().findings(), pool);
            List<String> reasons = new ArrayList<>(s.rejections());
            v.rejected().forEach(r -> reasons.add(abbreviate(r.finding().statement()) + " -> " + r.reason()));
            return Map.of(VERIFICATION, v, REJECTIONS, reasons,
                    TRAIL, "verified " + v.verified().size() + ", rejected " + v.rejected().size());
        });
    }

    // ---- 8. report generation -----------------------------------------------------------------------------------
    public Map<String, Object> reportGeneration(DpiAgentState s) {
        // Must not fail: builds the report from whatever the state holds, including failure runs.
        String failure = s.failure();
        VerificationResult v = s.verification();
        List<Finding> verified = v == null ? List.of() : v.verified();
        Outcome outcome = failure != null ? Outcome.FAILED
                : verified.isEmpty() ? Outcome.INSUFFICIENT_EVIDENCE : Outcome.FINDINGS_VERIFIED;

        Set<String> citedIds = verified.stream().flatMap(f -> f.evidenceIds().stream()).collect(Collectors.toSet());
        List<Evidence> pool = new ArrayList<>(s.relevant());
        pool.addAll(s.knowledge());
        Map<String, Evidence> byId = pool.stream()
                .collect(Collectors.toMap(Evidence::id, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        List<Evidence> supporting = citedIds.stream().map(byId::get).filter(e -> e != null && e.isCurrent()).toList();
        List<Evidence> related = s.knowledge();

        List<IncidentReport.TimelineEntry> timeline = supporting.stream()
                .filter(e -> e.timestamp() != null).sorted(Comparator.comparing(Evidence::timestamp))
                .map(e -> new IncidentReport.TimelineEntry(e.timestamp(), e.service(),
                        e.level() == null ? "" : e.level().name(), e.id(), abbreviate(e.excerpt()))).toList();
        List<String> services = supporting.stream().map(Evidence::service).distinct().sorted().toList();

        List<String> limitations = new ArrayList<>(List.of(
                "Generated by an LLM and gated by a deterministic evidence check; an engineer must validate it.",
                "No remediation has been or will be executed automatically."));
        if (failure != null) {
            limitations.add("Analysis did not complete: " + failure);
        }
        if (related.stream().anyMatch(e -> !"ENGINEER_VALIDATED".equals(e.validationStatus()))) {
            limitations.add("Some related knowledge documents are UNVERIFIED.");
        }
        List<String> actions = s.rca() == null ? List.of() : s.rca().recommendedChecks();
        String summary = s.rca() == null || s.rca().summary() == null ? "" : s.rca().summary();
        if (outcome == Outcome.INSUFFICIENT_EVIDENCE) {
            summary = "Insufficient verified evidence to state a root cause.";
        } else if (outcome == Outcome.FAILED) {
            summary = "Analysis failed before a verified conclusion could be reached.";
        }
        try {
            summary = masking.maskText(summary);
        } catch (RuntimeException e) {
            summary = "[summary withheld: masking failed]";
        }
        IncidentReport report = new IncidentReport(s.analysisId(), masking.maskText(s.question()), clock.instant(), outcome,
                summary, services, timeline, verified, v == null ? List.of() : v.rejected(), supporting, related, actions,
                s.rca() == null ? List.of() : s.rca().openQuestions(), limitations, PENDING_REVIEW);
        return Map.of(REPORT, report, TRAIL, "report generated: " + outcome);
    }

    // ---- 9. engineer review (human-in-the-loop gate) ----------------------------------------------------------
    /** Marks the report as awaiting a human decision and ends the run. The decision is recorded via the review API. */
    public Map<String, Object> engineerReview(DpiAgentState s) {
        return Map.of(TRAIL, "awaiting engineer review");
    }

    // ---- routing -----------------------------------------------------------------------------------------------
    public String afterGrading(DpiAgentState s) {
        if (stop(s)) {
            return "report";
        }
        if (!s.relevant().isEmpty()) {
            return "analyse";
        }
        return s.retrievalAttempts() <= props.maxRetrievalRetries() ? "rewrite" : "report";
    }

    public String afterVerification(DpiAgentState s) {
        if (stop(s)) {
            return "report";
        }
        boolean none = s.verification().verified().isEmpty();
        return none && s.verificationAttempts() <= props.maxVerificationRetries() && !s.rca().findings().isEmpty()
                ? "retry" : "report";
    }

    public String afterStep(DpiAgentState s) {
        return stop(s) ? "report" : "next";
    }

    private boolean stop(DpiAgentState s) {
        if (s.failure() != null) {
            return true;
        }
        return Duration.between(s.startedAt(), clock.instant()).compareTo(props.timeout()) > 0;
    }

    private Map<String, Object> guarded(String node, DpiAgentState s, java.util.function.Supplier<Map<String, Object>> body) {
        if (Duration.between(s.startedAt(), clock.instant()).compareTo(props.timeout()) > 0) {
            return Map.of(FAILURE, "TIMEOUT before " + node, TRAIL, node + ": timeout");
        }
        try {
            return body.get();
        } catch (RuntimeException e) {
            // Log the exception type only: messages from the LLM / stores could echo sensitive content.
            // node threads do not inherit the request MDC, so the analysis id is logged explicitly
            log.warn("agent node {} failed for analysis {}: {}", node, s.analysisId(), e.getClass().getSimpleName());
            return Map.of(FAILURE, node + " failed: " + e.getClass().getSimpleName(), TRAIL, node + ": failed");
        }
    }

    private static List<Evidence> dedupe(List<Evidence> in) {
        return new ArrayList<>(in.stream().collect(Collectors.toMap(Evidence::id, Function.identity(), (a, b) -> a,
                LinkedHashMap::new)).values());
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        String one = s.replaceAll("\\s+", " ").strip();
        return one.length() <= 240 ? one : one.substring(0, 240) + "…";
    }
}
