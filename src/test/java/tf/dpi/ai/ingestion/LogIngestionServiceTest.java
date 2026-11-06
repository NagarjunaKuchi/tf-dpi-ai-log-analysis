package tf.dpi.ai.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tf.dpi.ai.chunking.ChunkingProperties;
import tf.dpi.ai.chunking.LogChunker;
import tf.dpi.ai.elasticsearch.LogPage;
import tf.dpi.ai.elasticsearch.LogSource;
import tf.dpi.ai.elasticsearch.ReadRequest;
import tf.dpi.ai.embedding.ChunkEmbedder;
import tf.dpi.ai.embedding.EmbeddedChunk;
import tf.dpi.ai.exception.DependencyUnavailableException;
import tf.dpi.ai.exception.EmbeddingUnavailableException;
import tf.dpi.ai.exception.InvalidEmbeddingException;
import tf.dpi.ai.masking.EventSanitizer;
import tf.dpi.ai.masking.MaskingEngine;
import tf.dpi.ai.masking.MaskingPolicy;
import tf.dpi.ai.masking.StructuredLogMasker;
import tf.dpi.ai.model.LogChunk;
import tf.dpi.ai.model.LogEvent;
import tf.dpi.ai.model.LogLevel;
import tf.dpi.ai.observability.IngestionMetrics;

class LogIngestionServiceTest {

    private static final Instant T0 = Instant.parse("2026-11-06T10:00:00Z");

    // ---- fakes -------------------------------------------------------------------------------------------
    static class FakeSource implements LogSource {
        final List<LogPage> pages = new ArrayList<>();
        final List<ReadRequest> requests = new ArrayList<>();
        List<LogEvent> context = List.of();
        int calls;

        @Override
        public LogPage readPage(ReadRequest request) {
            requests.add(request);
            return calls < pages.size() ? pages.get(calls++) : new LogPage(List.of(), List.of(), List.of(), true);
        }

        @Override
        public List<LogEvent> fetchContext(String service, String host, String traceId, Instant anchorTime) {
            return context;
        }
    }

    static class FakeStore implements CheckpointStore, BatchCommitter {
        Checkpoint checkpoint;
        final List<EmbeddedChunk> stored = new ArrayList<>();
        final List<DeadLetter> deadLetters = new ArrayList<>();
        int commitAttempts;
        int failCommitsRemaining;

        @Override
        public Optional<Checkpoint> load(String streamName) {
            return Optional.ofNullable(checkpoint);
        }

        @Override
        public void commit(String streamName, List<EmbeddedChunk> chunks, List<DeadLetter> dead, Checkpoint next) {
            commitAttempts++;
            if (failCommitsRemaining-- > 0) {
                throw new DependencyUnavailableException("PostgreSQL unavailable", new RuntimeException("down"));
            }
            chunks.stream().filter(c -> !c.alreadyStored()).forEach(stored::add);
            deadLetters.addAll(dead);
            checkpoint = next;
        }
    }

    static class FakeEmbedder implements ChunkEmbedder {
        final List<String> seenContents = new ArrayList<>();
        RuntimeException failure;
        String poisonMarker;

        @Override
        public List<EmbeddedChunk> embed(List<LogChunk> chunks) {
            if (failure != null) {
                throw failure;
            }
            List<EmbeddedChunk> out = new ArrayList<>();
            for (LogChunk c : chunks) {
                if (poisonMarker != null && c.content().contains(poisonMarker)) {
                    throw new InvalidEmbeddingException("bad vector");
                }
                seenContents.add(c.content());
                out.add(new EmbeddedChunk(c, "sha-" + c.chunkId(), new float[] {0.1f, 0.2f}));
            }
            return out;
        }
    }

    private final FakeSource source = new FakeSource();
    private final FakeStore store = new FakeStore();
    private final FakeEmbedder embedder = new FakeEmbedder();
    private LogIngestionService service;
    private IngestionProperties props;

    @BeforeEach
    void setUp() {
        props = new IngestionProperties(true, "test-stream", 100, 5, 3, Duration.ofMillis(1), Duration.ofMinutes(5),
                Duration.ofHours(24), LogLevel.WARN, Duration.ofDays(30), Duration.ofSeconds(60));
        MaskingEngine engine = new MaskingEngine(MaskingPolicy.defaults());
        service = build(source, embedder);
        this.engine = engine;
    }

    private MaskingEngine engine;

    private LogIngestionService build(LogSource src, ChunkEmbedder emb) {
        MaskingEngine eng = new MaskingEngine(MaskingPolicy.defaults());
        EventSanitizer sanitizer = new EventSanitizer(eng, new StructuredLogMasker(eng, new ObjectMapper()));
        IngestionMetrics metrics = new IngestionMetrics(new SimpleMeterRegistry(), Clock.fixed(T0, ZoneOffset.UTC));
        return new LogIngestionService(src, store, sanitizer, new LogChunker(ChunkingProperties.defaults()), emb,
                store, props, metrics, Clock.fixed(T0, ZoneOffset.UTC),
                new RetryExecutor(props.maxBatchAttempts(), props.retryBackoff(), ms -> { }));
    }

    private static LogEvent event(String id, int secs, String msg) {
        return new LogEvent(id, "idx", T0.plusSeconds(secs), "db-pool", "h1", LogLevel.ERROR, "", "DB-001", "", msg,
                "", Map.of());
    }

    private static LogPage page(boolean exhausted, int lastSecs, String lastId, LogEvent... events) {
        return new LogPage(List.of(events), List.of(), List.of(Long.toString(T0.plusSeconds(lastSecs).toEpochMilli()),
                lastId), exhausted);
    }

    // ---- tests -------------------------------------------------------------------------------------------
    @Test
    void happyPathStoresChunksAndAdvancesCheckpointToLastRecord() {
        source.pages.add(page(true, 2, "b", event("a", 1, "connection refused"), event("b", 2, "connection refused")));

        IngestionResult r = service.runOnce();

        assertThat(r.caughtUp()).isTrue();
        assertThat(r.batches()).isEqualTo(1);
        assertThat(store.stored).hasSize(1);
        assertThat(store.checkpoint.sortKey()).containsExactly(Long.toString(T0.plusSeconds(2).toEpochMilli()), "b");
        assertThat(store.deadLetters).isEmpty();
    }

    @Test
    void secretsAreMaskedBeforeTheEmbedderAndTheStoreSeeThem() {
        source.pages.add(page(true, 1, "a", event("a", 1,
                "login failed password=S3cr3tPass! user ops@example.com jdbc:postgresql://u:HiddenPw@db/x")));

        service.runOnce();

        assertThat(embedder.seenContents).isNotEmpty();
        for (String content : embedder.seenContents) {
            assertThat(content).doesNotContain("S3cr3tPass!").doesNotContain("ops@example.com")
                    .doesNotContain("HiddenPw").contains("[MASKED:");
        }
        for (EmbeddedChunk c : store.stored) {
            assertThat(c.chunk().content()).doesNotContain("S3cr3tPass!").doesNotContain("HiddenPw");
        }
    }

    @Test
    void resumesFromStoredCheckpointUsingSearchAfter() {
        store.checkpoint = new Checkpoint(T0.minusSeconds(60), List.of("123", "zz"));
        service.runOnce();
        ReadRequest first = source.requests.get(0);
        assertThat(first.searchAfter()).containsExactly("123", "zz");
        assertThat(first.afterTimestamp()).isEqualTo(T0.minusSeconds(60));
        assertThat(first.minLevel()).isEqualTo(LogLevel.WARN);
    }

    @Test
    void firstRunStartsAtLookbackWindow() {
        service.runOnce();
        assertThat(source.requests.get(0).afterTimestamp()).isEqualTo(T0.minus(Duration.ofHours(24)));
        assertThat(source.requests.get(0).searchAfter()).isEmpty();
    }

    @Test
    void commitOutageRetriesThenAbortsWithoutAdvancingCheckpointOrDeadLettering() {
        source.pages.add(page(true, 1, "a", event("a", 1, "boom")));
        store.failCommitsRemaining = 99;

        assertThatThrownBy(() -> service.runOnce()).isInstanceOf(DependencyUnavailableException.class);

        assertThat(store.commitAttempts).isEqualTo(3); // maxBatchAttempts
        assertThat(store.checkpoint).isNull();
        assertThat(store.deadLetters).isEmpty();
        assertThat(store.stored).isEmpty();
    }

    @Test
    void transientCommitFailureIsRetriedAndSucceeds() {
        source.pages.add(page(true, 1, "a", event("a", 1, "boom")));
        store.failCommitsRemaining = 2;
        service.runOnce();
        assertThat(store.commitAttempts).isEqualTo(3);
        assertThat(store.checkpoint).isNotNull();
        assertThat(store.stored).hasSize(1);
    }

    @Test
    void embeddingModelOutageAbortsRunAndDeadLettersNothing() {
        source.pages.add(page(true, 1, "a", event("a", 1, "boom")));
        embedder.failure = new EmbeddingUnavailableException("down", new RuntimeException());

        assertThatThrownBy(() -> service.runOnce()).isInstanceOf(EmbeddingUnavailableException.class);

        assertThat(store.checkpoint).isNull();
        assertThat(store.deadLetters).isEmpty();
        assertThat(store.commitAttempts).isZero();
    }

    @Test
    void unmappableSourceRecordsAreDeadLetteredInTheSameCommitAndCheckpointPassesThem() {
        LogPage p = new LogPage(List.of(event("good", 1, "boom")),
                List.of(new LogPage.RejectedRecord("bad", "idx", "timestamp missing")),
                List.of(Long.toString(T0.plusSeconds(5).toEpochMilli()), "bad"), true);
        source.pages.add(p);

        service.runOnce();

        assertThat(store.deadLetters).hasSize(1);
        assertThat(store.deadLetters.get(0).stage()).isEqualTo(DeadLetter.Stage.VALIDATE);
        assertThat(store.deadLetters.get(0).esDocId()).isEqualTo("bad");
        assertThat(store.stored).hasSize(1);
        assertThat(store.checkpoint.sortKey().get(1)).isEqualTo("bad");
    }

    @Test
    void maskingFailureDeadLettersOnlyThatRecordFailClosed() {
        // oversized message exceeds the masker's size limit -> MaskingException -> dead letter, never embedded
        MaskingPolicy tiny = MaskingPolicy.defaults().withLimits(300, Duration.ofSeconds(2));
        MaskingEngine eng = new MaskingEngine(tiny);
        EventSanitizer sanitizer = new EventSanitizer(eng, new StructuredLogMasker(eng, new ObjectMapper()));
        LogIngestionService strict = new LogIngestionService(source, store, sanitizer,
                new LogChunker(ChunkingProperties.defaults()), embedder, store, props,
                new IngestionMetrics(new SimpleMeterRegistry(), Clock.fixed(T0, ZoneOffset.UTC)),
                Clock.fixed(T0, ZoneOffset.UTC), new RetryExecutor(3, Duration.ofMillis(1), ms -> { }));
        source.pages.add(page(true, 2, "big", event("ok", 1, "short"), event("big", 2, "x".repeat(1000))));

        strict.runOnce();

        assertThat(store.deadLetters).extracting(DeadLetter::esDocId).containsExactly("big");
        assertThat(store.deadLetters.get(0).stage()).isEqualTo(DeadLetter.Stage.MASK);
        assertThat(store.deadLetters.get(0).reason()).doesNotContain("xxx");
        assertThat(embedder.seenContents).noneMatch(c -> c.contains("xxxxxxxx"));
        assertThat(store.checkpoint.sortKey().get(1)).isEqualTo("big");
    }

    @Test
    void poisonChunkIsIsolatedAndDeadLetteredWhileOthersAreStored() {
        embedder.poisonMarker = "POISON";
        LogEvent poison = new LogEvent("p", "idx", T0.plusSeconds(1), "svcA", "h", LogLevel.ERROR, "", "A", "",
                "POISON payload", "", Map.of());
        LogEvent fine = new LogEvent("f", "idx", T0.plusSeconds(1), "svcB", "h", LogLevel.ERROR, "", "B", "",
                "fine payload", "", Map.of());
        source.pages.add(page(true, 1, "p", poison, fine));

        service.runOnce();

        assertThat(store.deadLetters).extracting(DeadLetter::esDocId).containsExactly("p");
        assertThat(store.deadLetters.get(0).stage()).isEqualTo(DeadLetter.Stage.EMBED);
        assertThat(store.stored).hasSize(1);
        assertThat(store.stored.get(0).chunk().sourceIds()).containsExactly("f");
        assertThat(store.checkpoint).isNotNull();
    }

    @Test
    void contextEventsAreMaskedAndIncludedInChunks() {
        source.context = List.of(new LogEvent("ctx", "idx", T0.plusSeconds(1), "db-pool", "h1", LogLevel.INFO, "",
                "", "", "pool resize token=abcd1234efgh", "", Map.of()));
        source.pages.add(page(true, 2, "a", event("a", 2, "connection refused")));

        service.runOnce();

        assertThat(store.stored).hasSize(1);
        String content = store.stored.get(0).chunk().content();
        assertThat(content).contains("pool resize").doesNotContain("abcd1234efgh");
        assertThat(store.stored.get(0).chunk().sourceIds()).contains("ctx", "a");
    }

    @Test
    void runIsBoundedByMaxBatchesPerRun() {
        for (int i = 0; i < 10; i++) {
            source.pages.add(page(false, i + 1, "id" + i, event("id" + i, i + 1, "boom " + i)));
        }
        IngestionResult r = service.runOnce();
        assertThat(r.batches()).isEqualTo(5);
        assertThat(r.caughtUp()).isFalse();
        assertThat(source.requests).hasSize(5);
    }

    @Test
    void stopRequestStopsBeforeReadingAnotherBatch() {
        source.pages.add(page(false, 1, "a", event("a", 1, "boom")));
        service.requestStop();
        IngestionResult r = service.runOnce();
        assertThat(r.stoppedEarly()).isTrue();
        assertThat(source.requests).isEmpty();
        assertThat(store.checkpoint).isNull();
    }

    @Test
    void reingestingTheSamePageIsIdempotentByChunkId() {
        source.pages.add(page(true, 1, "a", event("a", 1, "boom")));
        source.pages.add(page(true, 1, "a", event("a", 1, "boom")));
        service.runOnce();
        store.checkpoint = null; // simulate lost checkpoint -> same page re-read
        source.calls = 1;
        service.runOnce();
        assertThat(store.stored).hasSize(2);
        assertThat(store.stored.get(0).chunk().chunkId()).isEqualTo(store.stored.get(1).chunk().chunkId());
    }
}
