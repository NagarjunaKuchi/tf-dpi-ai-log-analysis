package tf.dpi.ai.chunking;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import tf.dpi.ai.model.LogChunk;
import tf.dpi.ai.model.LogEvent;
import tf.dpi.ai.model.LogLevel;

/**
 * Groups already-sanitized events and splits groups into token-bounded chunks.
 *
 * <p>Grouping key: the trace id when present (traces legitimately span services); otherwise
 * service + host + (error code, else exception type). Within a key, a new group starts when the gap between
 * consecutive events exceeds {@code maxGap} or the group would span more than {@code maxSpan}. Because repeated
 * errors from one component share a key and fall within the gap, retry bursts stay together.
 *
 * <p>An event (message plus complete stack trace) is never split across chunks. If a single event alone exceeds
 * the budget, deep frames are elided while every exception header and "Caused by" line is kept, and the chunk is
 * flagged {@code truncated=true}.
 */
public final class LogChunker {

    private static final int CHARS_PER_TOKEN = 4;

    private final ChunkingProperties props;

    public LogChunker(ChunkingProperties props) {
        this.props = props;
    }

    public List<LogChunk> chunk(List<LogEvent> events) {
        List<LogEvent> sorted = new ArrayList<>(events);
        sorted.sort(Comparator.comparing(LogEvent::timestamp).thenComparing(LogEvent::id));

        // 1. Events that identify themselves (trace id, error code or exception type) form groups by key + time.
        Map<String, List<LogEvent>> byKey = new LinkedHashMap<>();
        List<LogEvent> loose = new ArrayList<>();
        for (LogEvent e : sorted) {
            if (isLoose(e)) {
                loose.add(e);
            } else {
                byKey.computeIfAbsent(groupKey(e), k -> new ArrayList<>()).add(e);
            }
        }
        List<Group> groups = new ArrayList<>();
        for (Map.Entry<String, List<LogEvent>> entry : byKey.entrySet()) {
            for (List<LogEvent> g : splitByTime(entry.getValue())) {
                groups.add(new Group(entry.getKey(), new ArrayList<>(g)));
            }
        }

        // 2. "Loose" events (no trace, code or exception: typically INFO/DEBUG context) attach to the closest
        //    identified group of the same service and host; otherwise they form their own time-based groups.
        Map<String, List<LogEvent>> orphans = new LinkedHashMap<>();
        for (LogEvent e : loose) {
            Group target = closestGroup(groups, e);
            if (target != null) {
                target.events.add(e);
            } else {
                orphans.computeIfAbsent(groupKey(e), k -> new ArrayList<>()).add(e);
            }
        }
        for (Map.Entry<String, List<LogEvent>> entry : orphans.entrySet()) {
            for (List<LogEvent> g : splitByTime(entry.getValue())) {
                groups.add(new Group(entry.getKey(), new ArrayList<>(g)));
            }
        }

        List<LogChunk> chunks = new ArrayList<>();
        for (Group g : groups) {
            g.events.sort(Comparator.comparing(LogEvent::timestamp).thenComparing(LogEvent::id));
            chunks.addAll(toChunks(g.key, g.events));
        }
        chunks.sort(Comparator.comparing(LogChunk::fromTimestamp).thenComparing(LogChunk::chunkId));
        return chunks;
    }

    private static boolean isLoose(LogEvent e) {
        return e.traceId().isBlank() && e.errorCode().isBlank() && e.exceptionType().isBlank();
    }

    private Group closestGroup(List<Group> groups, LogEvent e) {
        Group best = null;
        long bestDistance = Long.MAX_VALUE;
        String prefix = "svc:" + e.service() + "|host:" + e.host() + "|";
        for (Group g : groups) {
            if (!g.key.startsWith(prefix)) {
                continue;
            }
            Instant first = g.events.get(0).timestamp();
            Instant last = g.events.get(g.events.size() - 1).timestamp();
            Instant t = e.timestamp();
            long distance = t.isBefore(first) ? Duration.between(t, first).toMillis()
                    : t.isAfter(last) ? Duration.between(last, t).toMillis() : 0;
            if (distance <= props.maxGap().toMillis() && distance < bestDistance) {
                best = g;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static final class Group {
        final String key;
        final List<LogEvent> events;

        Group(String key, List<LogEvent> events) {
            this.key = key;
            this.events = events;
        }
    }

    static String groupKey(LogEvent e) {
        if (!e.traceId().isBlank()) {
            return "trace:" + e.traceId();
        }
        String discriminator = !e.errorCode().isBlank() ? e.errorCode()
                : !e.exceptionType().isBlank() ? e.exceptionType() : "-";
        return "svc:" + e.service() + "|host:" + e.host() + "|err:" + discriminator;
    }

    private List<List<LogEvent>> splitByTime(List<LogEvent> ordered) {
        List<List<LogEvent>> groups = new ArrayList<>();
        List<LogEvent> current = new ArrayList<>();
        for (LogEvent e : ordered) {
            if (!current.isEmpty()) {
                Instant prev = current.get(current.size() - 1).timestamp();
                Instant first = current.get(0).timestamp();
                boolean gap = Duration.between(prev, e.timestamp()).compareTo(props.maxGap()) > 0;
                boolean span = Duration.between(first, e.timestamp()).compareTo(props.maxSpan()) > 0;
                if (gap || span) {
                    groups.add(current);
                    current = new ArrayList<>();
                }
            }
            current.add(e);
        }
        if (!current.isEmpty()) {
            groups.add(current);
        }
        return groups;
    }

    private List<LogChunk> toChunks(String groupKey, List<LogEvent> group) {
        List<RenderedEvent> rendered = new ArrayList<>(group.size());
        for (LogEvent e : group) {
            rendered.add(render(e));
        }

        List<List<RenderedEvent>> parts = new ArrayList<>();
        List<RenderedEvent> current = new ArrayList<>();
        int currentTokens = 0;
        for (RenderedEvent r : rendered) {
            if (!current.isEmpty() && currentTokens + r.tokens > props.maxChunkTokens()) {
                parts.add(current);
                current = new ArrayList<>(overlapOf(current, r.tokens));
                currentTokens = current.stream().mapToInt(x -> x.tokens).sum();
            }
            current.add(r);
            currentTokens += r.tokens;
        }
        if (!current.isEmpty()) {
            parts.add(current);
        }

        List<LogChunk> chunks = new ArrayList<>(parts.size());
        for (int i = 0; i < parts.size(); i++) {
            chunks.add(build(groupKey, parts.get(i), i, parts.size()));
        }
        return chunks;
    }

    /** Trailing events to repeat as context, limited so that overlap plus the next event still fits the budget. */
    private List<RenderedEvent> overlapOf(List<RenderedEvent> previous, int nextTokens) {
        List<RenderedEvent> overlap = new ArrayList<>();
        int tokens = nextTokens;
        for (int i = previous.size() - 1; i >= 0 && overlap.size() < props.overlapEvents(); i--) {
            RenderedEvent candidate = previous.get(i);
            if (tokens + candidate.tokens > props.maxChunkTokens()) {
                break;
            }
            overlap.add(0, candidate);
            tokens += candidate.tokens;
        }
        return overlap;
    }

    private LogChunk build(String groupKey, List<RenderedEvent> events, int index, int total) {
        StringBuilder content = new StringBuilder();
        List<String> ids = new ArrayList<>();
        boolean truncated = false;
        int tokens = 0;
        Map<String, Integer> services = new HashMap<>();
        Map<String, Integer> hosts = new HashMap<>();
        Map<String, Integer> codes = new HashMap<>();
        TreeSet<String> exceptionTypes = new TreeSet<>();
        LogLevel level = LogLevel.TRACE;
        Instant from = events.get(0).event.timestamp();
        Instant to = from;
        String traceId = "";

        for (RenderedEvent r : events) {
            if (content.length() > 0) {
                content.append('\n');
            }
            content.append(r.text);
            ids.add(r.event.id());
            truncated |= r.truncated;
            tokens += r.tokens;
            LogEvent e = r.event;
            services.merge(e.service(), 1, Integer::sum);
            if (!e.host().isBlank()) {
                hosts.merge(e.host(), 1, Integer::sum);
            }
            if (!e.errorCode().isBlank()) {
                codes.merge(e.errorCode(), 1, Integer::sum);
            }
            if (!e.exceptionType().isBlank()) {
                exceptionTypes.add(e.exceptionType());
            }
            if (e.level().compareTo(level) > 0) {
                level = e.level();
            }
            if (e.timestamp().isBefore(from)) {
                from = e.timestamp();
            }
            if (e.timestamp().isAfter(to)) {
                to = e.timestamp();
            }
            if (traceId.isBlank()) {
                traceId = e.traceId();
            }
        }

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("chunkerVersion", ChunkingProperties.CHUNKER_VERSION);
        meta.put("groupKey", groupKey);
        meta.put("chunkIndex", Integer.toString(index));
        meta.put("chunkCount", Integer.toString(total));
        meta.put("eventCount", Integer.toString(events.size()));
        meta.put("tokenEstimate", Integer.toString(tokens));
        meta.put("truncated", Boolean.toString(truncated));
        meta.put("services", String.join(",", new TreeSet<>(services.keySet())));
        meta.put("exceptionTypes", String.join(",", exceptionTypes));

        return new LogChunk(
                chunkId(ids, index),
                mostFrequent(services),
                level,
                from,
                to,
                mostFrequent(codes),
                traceId,
                mostFrequent(hosts),
                content.toString(),
                ids,
                meta);
    }

    private static String mostFrequent(Map<String, Integer> counts) {
        return counts.entrySet().stream()
                .max(Map.Entry.<String, Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey)
                .orElse("");
    }

    /** Deterministic: same source documents and chunker version always produce the same id. */
    static String chunkId(List<String> sourceIds, int index) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(("v" + ChunkingProperties.CHUNKER_VERSION + "|" + index + "|").getBytes(StandardCharsets.UTF_8));
            for (String id : sourceIds) {
                sha.update(id.getBytes(StandardCharsets.UTF_8));
                sha.update((byte) 0);
            }
            return java.util.HexFormat.of().formatHex(sha.digest(), 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private RenderedEvent render(LogEvent e) {
        StringBuilder header = new StringBuilder()
                .append(e.timestamp()).append(' ')
                .append(e.level()).append(' ')
                .append(e.service());
        if (!e.host().isBlank()) {
            header.append(" host=").append(e.host());
        }
        if (!e.traceId().isBlank()) {
            header.append(" trace=").append(e.traceId());
        }
        if (!e.errorCode().isBlank()) {
            header.append(" code=").append(e.errorCode());
        }
        header.append(" - ").append(e.message());

        String text = e.stackTrace().isBlank() ? header.toString() : header + "\n" + e.stackTrace();
        boolean truncated = false;
        int budgetChars = props.maxChunkTokens() * CHARS_PER_TOKEN;
        if (text.length() > budgetChars) {
            text = elideFrames(header.toString(), e.stackTrace());
            truncated = true;
            if (text.length() > budgetChars) {
                text = text.substring(0, Math.max(0, budgetChars - 40)) + "\n... [truncated to token budget]";
            }
        }
        return new RenderedEvent(e, text, estimateTokens(text), truncated);
    }

    /** Keeps exception headers, "Caused by" lines and the first N frames of each exception; elides the rest. */
    private String elideFrames(String header, String stackTrace) {
        StringBuilder out = new StringBuilder(header);
        int framesKept = 0;
        int elided = 0;
        for (String line : stackTrace.split("\\R")) {
            boolean frame = line.stripLeading().startsWith("at ") || line.stripLeading().startsWith("... ");
            if (!frame) {
                if (elided > 0) {
                    out.append("\n\t... ").append(elided).append(" frames elided");
                    elided = 0;
                }
                framesKept = 0;
                out.append('\n').append(line);
            } else if (framesKept < props.maxFramesPerException()) {
                framesKept++;
                out.append('\n').append(line);
            } else {
                elided++;
            }
        }
        if (elided > 0) {
            out.append("\n\t... ").append(elided).append(" frames elided");
        }
        return out.toString();
    }

    static int estimateTokens(String text) {
        return Math.max(1, (text.length() + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN);
    }

    private static final class RenderedEvent {
        final LogEvent event;
        final String text;
        final int tokens;
        final boolean truncated;

        RenderedEvent(LogEvent event, String text, int tokens, boolean truncated) {
            this.event = event;
            this.text = text;
            this.tokens = tokens;
            this.truncated = truncated;
        }
    }
}
