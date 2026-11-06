package tf.dpi.ai.ingestion;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tf.dpi.ai.chunking.LogChunker;
import tf.dpi.ai.elasticsearch.LogPage;
import tf.dpi.ai.elasticsearch.LogSource;
import tf.dpi.ai.elasticsearch.ReadRequest;
import tf.dpi.ai.embedding.ChunkEmbedder;
import tf.dpi.ai.embedding.EmbeddedChunk;
import tf.dpi.ai.exception.InvalidEmbeddingException;
import tf.dpi.ai.masking.EventSanitizer;
import tf.dpi.ai.masking.MaskingException;
import tf.dpi.ai.model.LogChunk;
import tf.dpi.ai.model.LogEvent;
import tf.dpi.ai.observability.IngestionMetrics;

/**
 * Incremental ingestion: checkpoint -> read page -> validate -> mask -> context -> group/chunk -> embed -> commit.
 *
 * <p>Safety invariants:
 * <ul>
 *   <li>Only sanitized events reach the chunker, the embedder and the database.</li>
 *   <li>The checkpoint moves only inside the same transaction that stores the page's vectors and dead letters, so
 *       it never passes a record that is neither stored nor durably dead-lettered.</li>
 *   <li>Dependency outages (Elasticsearch, embedding model, database) abort the run after bounded retries and
 *       dead-letter nothing; the next run resumes from the last committed checkpoint.</li>
 *   <li>Only genuinely bad records (unmappable, masking cannot be completed safely, unusable embedding) are
 *       dead-lettered, with a content-free reason.</li>
 * </ul>
 * Pagination is inherently sequential per stream, so concurrency is bounded to one run per stream (the scheduler
 * additionally takes a cluster-wide lock). Re-ingestion is idempotent: chunk ids are deterministic and vectors are
 * upserted.
 */
public class LogIngestionService {

    private static final Logger log = LoggerFactory.getLogger(LogIngestionService.class);

    private final LogSource source;
    private final CheckpointStore checkpoints;
    private final EventSanitizer sanitizer;
    private final LogChunker chunker;
    private final ChunkEmbedder embedder;
    private final BatchCommitter committer;
    private final IngestionProperties props;
    private final IngestionMetrics metrics;
    private final Clock clock;
    private final RetryExecutor retry;
    private volatile boolean stopping;

    public LogIngestionService(LogSource source, CheckpointStore checkpoints, EventSanitizer sanitizer,
            LogChunker chunker, ChunkEmbedder embedder, BatchCommitter committer, IngestionProperties props,
            IngestionMetrics metrics, Clock clock, RetryExecutor retry) {
        this.source = source;
        this.checkpoints = checkpoints;
        this.sanitizer = sanitizer;
        this.chunker = chunker;
        this.embedder = embedder;
        this.committer = committer;
        this.props = props;
        this.metrics = metrics;
        this.clock = clock;
        this.retry = retry;
    }

    /** Graceful shutdown: the current batch finishes and commits, then the run stops. */
    public void requestStop() {
        stopping = true;
    }

    public IngestionResult runOnce() {
        String stream = props.streamName();
        Optional<Checkpoint> loaded = retry.call("load checkpoint", () -> checkpoints.load(stream));
        Checkpoint current = loaded.orElse(null);
        Instant start = current != null ? current.lastTimestamp() : clock.instant().minus(props.lookback());
        if (current != null) {
            metrics.checkpoint(current.lastTimestamp());
        }

        long read = 0;
        long stored = 0;
        long dead = 0;
        int batches = 0;
        boolean caughtUp = false;
        while (batches < props.maxBatchesPerRun()) {
            if (stopping) {
                return new IngestionResult(read, stored, dead, batches, false, true);
            }
            ReadRequest request = new ReadRequest(start, current == null ? List.of() : current.sortKey(), null,
                    props.minLevel(), Set.of(), Set.of(), null, null, props.batchSize());
            LogPage page = retry.call("read page", () -> source.readPage(request));
            if (page.isEmpty()) {
                caughtUp = true;
                break;
            }
            Checkpoint next = Checkpoint.fromSortKey(page.lastSortKey());
            BatchStats stats = processPage(stream, page, next);
            current = next;
            metrics.checkpoint(next.lastTimestamp());
            read += page.events().size() + page.rejected().size();
            stored += stats.chunksStored;
            dead += stats.deadLettered;
            batches++;
            if (page.exhausted()) {
                caughtUp = true;
                break;
            }
        }
        return new IngestionResult(read, stored, dead, batches, caughtUp, false);
    }

    private BatchStats processPage(String stream, LogPage page, Checkpoint next) {
        List<DeadLetter> deadLetters = new ArrayList<>();
        for (LogPage.RejectedRecord r : page.rejected()) {
            deadLetters.add(new DeadLetter(r.documentId(), r.index(), DeadLetter.Stage.VALIDATE, r.reason(), 1));
        }

        // Mask first: nothing below this point ever sees raw content.
        Map<String, LogEvent> sanitized = new LinkedHashMap<>();
        Set<String> primaryIds = new HashSet<>();
        List<LogEvent> rawAnchors = new ArrayList<>();
        long maskedCount = 0;
        for (LogEvent raw : page.events()) {
            try {
                LogEvent clean = sanitizer.sanitize(raw);
                if (sanitizer.wasMasked(raw, clean)) {
                    maskedCount++;
                }
                sanitized.put(clean.id(), clean);
                primaryIds.add(clean.id());
                rawAnchors.add(raw);
            } catch (MaskingException e) {
                deadLetters.add(new DeadLetter(raw.id(), raw.sourceIndex(), DeadLetter.Stage.MASK,
                        "masking could not be completed safely (fail closed)", 1));
            }
        }

        addContext(rawAnchors, sanitized);

        List<LogChunk> chunks = chunker.chunk(new ArrayList<>(sanitized.values()));
        List<EmbeddedChunk> embedded = embedChunks(chunks, primaryIds, deadLetters);

        final List<DeadLetter> toCommit = List.copyOf(deadLetters);
        retry.call("commit batch", () -> {
            committer.commit(stream, embedded, toCommit, next);
            return null;
        });

        long newVectors = embedded.stream().filter(e -> !e.alreadyStored()).count();
        metrics.ingested(primaryIds.size());
        metrics.masked(maskedCount);
        metrics.chunks(chunks.size());
        metrics.deadLettered(toCommit.size());
        if (!toCommit.isEmpty()) {
            log.warn("Dead-lettered {} record(s) in this batch (stages: {})", toCommit.size(),
                    toCommit.stream().map(d -> d.stage().name()).distinct().toList());
        }
        return new BatchStats(newVectors, toCommit.size());
    }

    /** Adds sanitized WARN/ERROR surrounding context; one lookup per distinct trace or service/host/window. */
    private void addContext(List<LogEvent> rawAnchors, Map<String, LogEvent> sanitized) {
        Set<String> looked = new HashSet<>();
        long skipped = 0;
        for (LogEvent anchor : rawAnchors) {
            String key = !anchor.traceId().isBlank() ? "t:" + anchor.traceId()
                    : "s:" + anchor.service() + "|" + anchor.host() + "|" + anchor.timestamp().getEpochSecond() / 60;
            if (!looked.add(key)) {
                continue;
            }
            List<LogEvent> context = retry.call("fetch context",
                    () -> source.fetchContext(anchor.service(), anchor.host(), anchor.traceId(), anchor.timestamp()));
            for (LogEvent raw : context) {
                if (sanitized.containsKey(raw.id())) {
                    continue;
                }
                try {
                    sanitized.put(raw.id(), sanitizer.sanitize(raw));
                } catch (MaskingException e) {
                    skipped++; // context only: leaving it out is safe; it is not a primary record
                }
            }
        }
        if (skipped > 0) {
            metrics.contextSkipped(skipped);
        }
    }

    private List<EmbeddedChunk> embedChunks(List<LogChunk> chunks, Set<String> primaryIds,
            List<DeadLetter> deadLetters) {
        if (chunks.isEmpty()) {
            return List.of();
        }
        try {
            return retry.call("embed chunks", () -> embedder.embed(chunks));
        } catch (InvalidEmbeddingException batchFailure) {
            // A bad vector somewhere in the batch: isolate chunk by chunk so one poison chunk cannot block the rest.
            List<EmbeddedChunk> good = new ArrayList<>();
            for (LogChunk chunk : chunks) {
                try {
                    good.addAll(retry.call("embed chunk", () -> embedder.embed(List.of(chunk))));
                } catch (InvalidEmbeddingException e) {
                    for (String id : chunk.sourceIds()) {
                        if (primaryIds.contains(id)) {
                            deadLetters.add(new DeadLetter(id, "", DeadLetter.Stage.EMBED,
                                    "embedding model returned an unusable vector", 1));
                        }
                    }
                }
            }
            return good;
        }
    }

    private record BatchStats(long chunksStored, long deadLettered) {
    }
}
