package tf.dpi.ai.chunking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tf.dpi.ai.model.LogChunk;
import tf.dpi.ai.model.LogEvent;
import tf.dpi.ai.model.LogLevel;

class LogChunkerTest {

    private static final Instant T0 = Instant.parse("2026-11-06T10:43:18Z");

    private static LogEvent event(String id, int secs, String service, LogLevel level, String trace, String code,
            String message, String stack) {
        return new LogEvent(id, "logs-2026.11.06", T0.plusSeconds(secs), service, "host-1", level, trace, code,
                "", message, stack, Map.of());
    }

    @Test
    void groupsByTraceAcrossServicesInChronologicalOrder() {
        List<LogChunk> chunks = new LogChunker(ChunkingProperties.defaults()).chunk(List.of(
                event("e3", 5, "auth-api", LogLevel.WARN, "t1", "", "retry 1 of 3", ""),
                event("e1", 1, "db-pool", LogLevel.ERROR, "t1", "DB-001", "connection refused", ""),
                event("e2", 3, "auth-api", LogLevel.ERROR, "t1", "DB-001", "db unavailable", "")));
        assertThat(chunks).hasSize(1);
        LogChunk c = chunks.get(0);
        assertThat(c.sourceIds()).containsExactly("e1", "e2", "e3");
        assertThat(c.level()).isEqualTo(LogLevel.ERROR);
        assertThat(c.traceId()).isEqualTo("t1");
        assertThat(c.errorCode()).isEqualTo("DB-001");
        assertThat(c.fromTimestamp()).isEqualTo(T0.plusSeconds(1));
        assertThat(c.toTimestamp()).isEqualTo(T0.plusSeconds(5));
        assertThat(c.metadata().get("services")).isEqualTo("auth-api,db-pool");
        assertThat(c.content().indexOf("connection refused")).isLessThan(c.content().indexOf("retry 1 of 3"));
    }

    @Test
    void groupsRetryBurstsWithoutTraceByServiceHostAndCode() {
        List<LogChunk> chunks = new LogChunker(ChunkingProperties.defaults()).chunk(List.of(
                event("a", 0, "activemq-client", LogLevel.ERROR, "", "MQ-1", "Could not connect to broker", ""),
                event("b", 10, "activemq-client", LogLevel.ERROR, "", "MQ-1", "Could not connect to broker", ""),
                event("c", 20, "activemq-client", LogLevel.ERROR, "", "MQ-1", "Could not connect to broker", "")));
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).sourceIds()).containsExactly("a", "b", "c");
    }

    @Test
    void largeGapStartsNewGroup() {
        List<LogChunk> chunks = new LogChunker(ChunkingProperties.defaults()).chunk(List.of(
                event("a", 0, "svc", LogLevel.ERROR, "", "X", "boom", ""),
                event("b", 3600, "svc", LogLevel.ERROR, "", "X", "boom", "")));
        assertThat(chunks).hasSize(2);
    }

    @Test
    void differentErrorCodesAreSeparateGroups() {
        List<LogChunk> chunks = new LogChunker(ChunkingProperties.defaults()).chunk(List.of(
                event("a", 0, "svc", LogLevel.ERROR, "", "X", "boom", ""),
                event("b", 1, "svc", LogLevel.ERROR, "", "Y", "bang", "")));
        assertThat(chunks).hasSize(2);
    }

    @Test
    void keepsStackTraceAndCauseChainInOneChunk() {
        String stack = "java.lang.IllegalStateException: pool\n\tat a.B.c(B.java:1)\n\tat a.B.d(B.java:2)\n"
                + "Caused by: java.net.ConnectException: Connection refused\n\tat java.base/Socket.connect(Socket.java:1)";
        List<LogChunk> chunks = new LogChunker(ChunkingProperties.defaults()).chunk(List.of(
                event("a", 0, "svc", LogLevel.ERROR, "t", "", "failed", stack)));
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).content()).contains("Caused by: java.net.ConnectException").contains("B.java:2");
        assertThat(chunks.get(0).metadata().get("truncated")).isEqualTo("false");
    }

    @Test
    void splitsLongGroupsWithinTokenBudgetAndNeverSplitsAnEvent() {
        ChunkingProperties props = new ChunkingProperties(64, Duration.ofSeconds(60), Duration.ofMinutes(10), 1, 8);
        List<LogEvent> events = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            events.add(event("e" + i, i, "svc", LogLevel.ERROR, "t", "X", "message number " + i + " ".repeat(60), ""));
        }
        List<LogChunk> chunks = new LogChunker(props).chunk(events);
        assertThat(chunks.size()).isGreaterThan(1);
        for (LogChunk c : chunks) {
            assertThat(Integer.parseInt(c.metadata().get("tokenEstimate"))).isLessThanOrEqualTo(64 + 5);
        }
        // every event is present in at least one chunk
        List<String> all = chunks.stream().flatMap(c -> c.sourceIds().stream()).distinct().toList();
        assertThat(all).hasSize(20);
    }

    @Test
    void oversizedStackTraceIsElidedButKeepsExceptionHeaders() {
        StringBuilder stack = new StringBuilder("java.lang.OutOfMemoryError: Java heap space");
        for (int i = 0; i < 400; i++) {
            stack.append("\n\tat pkg.Cls.m").append(i).append("(Cls.java:").append(i).append(')');
        }
        stack.append("\nCaused by: java.lang.RuntimeException: root\n\tat pkg.Root.go(Root.java:1)");
        ChunkingProperties props = new ChunkingProperties(256, Duration.ofSeconds(60), Duration.ofMinutes(10), 1, 5);
        List<LogChunk> chunks = new LogChunker(props).chunk(List.of(
                event("oom", 0, "svc", LogLevel.ERROR, "", "", "heap", stack.toString())));
        assertThat(chunks).hasSize(1);
        LogChunk c = chunks.get(0);
        assertThat(c.content()).contains("OutOfMemoryError: Java heap space").contains("Caused by: java.lang.RuntimeException");
        assertThat(c.content()).contains("frames elided");
        assertThat(c.metadata().get("truncated")).isEqualTo("true");
    }

    @Test
    void chunkIdsAreDeterministic() {
        List<LogEvent> events = List.of(event("a", 0, "svc", LogLevel.ERROR, "t", "X", "m", ""));
        String id1 = new LogChunker(ChunkingProperties.defaults()).chunk(events).get(0).chunkId();
        String id2 = new LogChunker(ChunkingProperties.defaults()).chunk(events).get(0).chunkId();
        assertThat(id1).isEqualTo(id2).hasSize(32);
    }

    @Test
    void emptyInputYieldsNoChunks() {
        assertThat(new LogChunker(ChunkingProperties.defaults()).chunk(List.of())).isEmpty();
    }
}
