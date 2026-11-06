package tf.dpi.ai.exception;

/**
 * A required dependency (Elasticsearch, PostgreSQL, embedding model, LLM) is unavailable after bounded retries.
 * Callers abort the current unit of work WITHOUT dead-lettering records: the records are fine, the dependency is not.
 */
public class DependencyUnavailableException extends RuntimeException {

    public DependencyUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
