package tf.dpi.ai.agent.graph;

/** Keys of the shared graph state. */
public final class AgentStateKeys {

    public static final String ANALYSIS_ID = "analysisId";
    public static final String QUESTION = "question";
    public static final String STARTED_AT = "startedAt";
    public static final String QUERY = "query";
    public static final String SEARCH_TEXT = "searchText";
    public static final String EVIDENCE = "evidence";
    public static final String RELEVANT = "relevant";
    public static final String KNOWLEDGE = "knowledge";
    public static final String RETRIEVAL_ATTEMPTS = "retrievalAttempts";
    public static final String VERIFICATION_ATTEMPTS = "verificationAttempts";
    public static final String RCA = "rca";
    public static final String VERIFICATION = "verification";
    public static final String REJECTIONS = "rejections";
    public static final String REPORT = "report";
    public static final String FAILURE = "failure";
    public static final String TRAIL = "trail";

    private AgentStateKeys() {
    }
}
