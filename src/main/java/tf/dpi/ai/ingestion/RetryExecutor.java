package tf.dpi.ai.ingestion;

import java.time.Duration;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tf.dpi.ai.exception.DependencyUnavailableException;

/** Bounded retry with exponential backoff for dependency outages. Never retries other failures. */
public class RetryExecutor {

    private static final Logger log = LoggerFactory.getLogger(RetryExecutor.class);

    private final int maxAttempts;
    private final Duration initialBackoff;
    private final Sleeper sleeper;
    private final java.util.function.IntConsumer onRetry;

    public RetryExecutor(int maxAttempts, Duration initialBackoff, java.util.function.IntConsumer onRetry) {
        this(maxAttempts, initialBackoff, Thread::sleep, onRetry);
    }

    RetryExecutor(int maxAttempts, Duration initialBackoff, Sleeper sleeper, java.util.function.IntConsumer onRetry) {
        this.maxAttempts = maxAttempts;
        this.initialBackoff = initialBackoff;
        this.sleeper = sleeper;
        this.onRetry = onRetry;
    }

    public <T> T call(String what, Supplier<T> action) {
        Duration backoff = initialBackoff;
        for (int attempt = 1;; attempt++) {
            try {
                return action.get();
            } catch (DependencyUnavailableException e) {
                if (attempt >= maxAttempts) {
                    throw e;
                }
                onRetry.accept(attempt);
                log.warn("{} failed on attempt {}/{} ({}); retrying in {} ms", what, attempt, maxAttempts,
                        e.getClass().getSimpleName(), backoff.toMillis());
                try {
                    sleeper.sleep(backoff.toMillis());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
                backoff = backoff.multipliedBy(2);
            }
        }
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }
}
