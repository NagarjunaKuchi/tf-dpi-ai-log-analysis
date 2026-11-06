package tf.dpi.ai.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tf.dpi.ai.exception.CorruptLogRecordException;
import tf.dpi.ai.exception.SourceUnavailableException;
import tf.dpi.ai.model.LogEvent;

/**
 * Read-only {@link LogSource}. Only {@code search} is ever invoked on the client: there is no index, update,
 * delete, bulk or PIT-open call anywhere in this class. Transient failures (I/O, 429, 502-504) are retried with
 * bounded exponential backoff; everything else fails immediately.
 */
public class ElasticsearchLogReader implements LogSource {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchLogReader.class);

    private final ElasticsearchClient client;
    private final ElasticsearchQueryBuilder queries;
    private final ElasticsearchLogMapper mapper;
    private final ElasticsearchProperties props;
    private final Timer queryTimer;
    private final Sleeper sleeper;

    public ElasticsearchLogReader(ElasticsearchClient client, ElasticsearchQueryBuilder queries,
            ElasticsearchLogMapper mapper, ElasticsearchProperties props, MeterRegistry meters) {
        this(client, queries, mapper, props, meters, Thread::sleep);
    }

    ElasticsearchLogReader(ElasticsearchClient client, ElasticsearchQueryBuilder queries,
            ElasticsearchLogMapper mapper, ElasticsearchProperties props, MeterRegistry meters, Sleeper sleeper) {
        this.client = client;
        this.queries = queries;
        this.mapper = mapper;
        this.props = props;
        this.sleeper = sleeper;
        this.queryTimer = Timer.builder("dpi.elasticsearch.query.latency")
                .description("Elasticsearch search latency").register(meters);
    }

    @Override
    public LogPage readPage(ReadRequest request) {
        SearchResponse<ObjectNode> response = search(queries.pageRequest(request));
        List<LogEvent> events = new ArrayList<>();
        List<LogPage.RejectedRecord> rejected = new ArrayList<>();
        List<String> lastKey = List.of();
        List<Hit<ObjectNode>> hits = response.hits().hits();
        for (Hit<ObjectNode> hit : hits) {
            try {
                events.add(mapper.map(hit.id(), hit.index(), hit.source()));
            } catch (CorruptLogRecordException e) {
                rejected.add(new LogPage.RejectedRecord(hit.id() == null ? "<missing>" : hit.id(), hit.index(),
                        e.getMessage()));
            }
            lastKey = sortKey(hit);
        }
        return new LogPage(events, rejected, lastKey, hits.size() < request.size());
    }

    @Override
    public List<LogEvent> fetchContext(String service, String host, String traceId, Instant anchorTime) {
        SearchResponse<ObjectNode> response = search(queries.contextRequest(service, host, traceId, anchorTime));
        List<LogEvent> events = new ArrayList<>();
        for (Hit<ObjectNode> hit : response.hits().hits()) {
            try {
                events.add(mapper.map(hit.id(), hit.index(), hit.source()));
            } catch (CorruptLogRecordException e) {
                log.warn("Skipping unmappable context document id={} reason={}", e.documentId(), e.getMessage());
            }
        }
        return events;
    }

    private static List<String> sortKey(Hit<ObjectNode> hit) {
        if (hit.sort() == null || hit.sort().size() < 2) {
            throw new IllegalStateException("Elasticsearch hit is missing the expected 2-part sort key");
        }
        return List.of(hit.sort().get(0)._toJsonString(), hit.sort().get(1).stringValue());
    }

    private SearchResponse<ObjectNode> search(SearchRequest request) {
        Duration backoff = props.initialBackoff();
        for (int attempt = 0;; attempt++) {
            try {
                return queryTimer.recordCallable(() -> client.search(request, ObjectNode.class));
            } catch (Exception e) {
                if (!retryable(e) || attempt >= props.maxRetries()) {
                    throw new SourceUnavailableException(
                            "Elasticsearch search failed after " + (attempt + 1) + " attempt(s)", e);
                }
                log.warn("Elasticsearch search attempt {} failed ({}); retrying in {} ms", attempt + 1,
                        e.getClass().getSimpleName(), backoff.toMillis());
                try {
                    sleeper.sleep(backoff.toMillis());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new SourceUnavailableException("Interrupted while waiting to retry Elasticsearch", ie);
                }
                backoff = backoff.multipliedBy(2).compareTo(props.maxBackoff()) > 0 ? props.maxBackoff()
                        : backoff.multipliedBy(2);
            }
        }
    }

    static boolean retryable(Exception e) {
        if (e instanceof IOException) {
            return true;
        }
        if (e instanceof ElasticsearchException ee) {
            int s = ee.status();
            return s == 429 || s == 502 || s == 503 || s == 504;
        }
        return false;
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }
}
