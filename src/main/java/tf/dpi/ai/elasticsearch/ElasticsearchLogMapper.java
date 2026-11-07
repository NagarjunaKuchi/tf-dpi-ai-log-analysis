package tf.dpi.ai.elasticsearch;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import tf.dpi.ai.exception.CorruptLogRecordException;
import tf.dpi.ai.model.LogEvent;
import tf.dpi.ai.model.LogLevel;

/**
 * Default, configuration-driven {@link LogEventMapper}: field names ({@code dpi.elasticsearch.fields}) and value
 * interpretation ({@code dpi.elasticsearch.mapping}) come from configuration. Only the fields the platform needs are carried
 * over (data minimisation). Failures raise {@link CorruptLogRecordException} with a content-free reason.
 */
public class ElasticsearchLogMapper implements LogEventMapper {

    private static final Pattern DEFAULT_EXCEPTION_HEAD =
            Pattern.compile("([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)+(?:Exception|Error|Throwable))\\b");
    /** First line of a Java stack trace frame ("\tat pkg.Class.method(File.java:12)"); everything from here on is the trace. */
    private static final Pattern FRAME_START = Pattern.compile("(?m)^[ \\t]*at [\\w$.<>/]+\\(");
    /** The exception pattern only looks at the start of the trace, which bounds the cost of an operator-supplied regex. */
    private static final int EXCEPTION_SCAN_CHARS = 1000;

    private final ElasticsearchProperties.Fields fields;
    private final Pattern exceptionHead;
    private final Pattern errorCodePattern;
    private final List<TimestampFormat> timestampFormats;
    private final Map<String, LogLevel> levelAliases;
    private final boolean numericAsSeconds;

    public ElasticsearchLogMapper(ElasticsearchProperties.Fields fields) {
        this(fields, ElasticsearchProperties.Mapping.defaults());
    }

    public ElasticsearchLogMapper(ElasticsearchProperties.Fields fields, ElasticsearchProperties.Mapping mapping) {
        this.fields = fields;
        this.exceptionHead = compileExceptionPattern(mapping.exceptionPattern());
        this.errorCodePattern = compileErrorCodePattern(mapping.errorCodePattern());
        ZoneId zone;
        try {
            zone = ZoneId.of(mapping.timestampZone());
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("dpi.elasticsearch.mapping.timestamp-zone is not a valid zone id");
        }
        List<TimestampFormat> formats = new ArrayList<>();
        for (String f : mapping.timestampFormats()) {
            formats.add(TimestampFormat.of(f, zone));
        }
        this.timestampFormats = List.copyOf(formats);
        this.numericAsSeconds = mapping.timestampFormats().contains("epoch_seconds")
                && !mapping.timestampFormats().contains("epoch_millis");
        Map<String, LogLevel> aliases = new java.util.HashMap<>();
        mapping.levelAliases().forEach((k, v) -> {
            try {
                aliases.put(k.trim().toUpperCase(Locale.ROOT), LogLevel.valueOf(v.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "dpi.elasticsearch.mapping.level-aliases: value for '" + k + "' is not a log level (TRACE..FATAL)");
            }
        });
        this.levelAliases = Map.copyOf(aliases);
    }

    private static Pattern compileErrorCodePattern(String configured) {
        if (configured == null || configured.isBlank()) {
            return null;
        }
        try {
            return Pattern.compile(configured);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("dpi.elasticsearch.mapping.error-code-pattern is not a valid regular expression");
        }
    }

    private static Pattern compileExceptionPattern(String configured) {
        if (configured == null || configured.isBlank()) {
            return DEFAULT_EXCEPTION_HEAD;
        }
        try {
            Pattern p = Pattern.compile(configured);
            if (p.matcher("").groupCount() < 1) {
                throw new IllegalArgumentException(
                        "dpi.elasticsearch.mapping.exception-pattern must contain a capturing group for the exception type");
            }
            return p;
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("dpi.elasticsearch.mapping.exception-pattern is not a valid regular expression");
        }
    }

    @Override
    public LogEvent map(String documentId, String index, JsonNode source) {
        if (documentId == null || documentId.isBlank()) {
            throw new CorruptLogRecordException("<missing>", "document has no id");
        }
        if (source == null || !source.isObject()) {
            throw new CorruptLogRecordException(documentId, "document has no _source object");
        }
        Instant timestamp = parseTimestamp(documentId, resolve(source, fields.timestamp()));
        String message = text(resolve(source, fields.message()));
        String stackTrace = text(resolve(source, fields.stackTrace()));
        String exceptionScanText = stackTrace;
        if (stackTrace.isBlank()) {
            // Many applications log the whole exception inside the message: split off the frames so message and trace are separate.
            Matcher frames = FRAME_START.matcher(message);
            if (frames.find() && frames.start() > 0 && !message.substring(0, frames.start()).isBlank()) {
                stackTrace = message.substring(frames.start()).stripTrailing();
                message = message.substring(0, frames.start()).stripTrailing();
            }
        }
        String exceptionType = text(resolve(source, fields.exceptionType()));
        if (exceptionType.isBlank()) {
            exceptionType = firstMatch(exceptionHead, exceptionScanText);
            if (exceptionType.isBlank()) {
                exceptionType = firstMatch(exceptionHead, message);
            }
        }
        String errorCode = text(resolve(source, fields.errorCode()));
        if (errorCode.isBlank() && errorCodePattern != null) {
            errorCode = firstMatch(errorCodePattern, message);
        }
        return new LogEvent(
                documentId,
                index,
                timestamp,
                text(resolve(source, fields.service())),
                text(resolve(source, fields.host())),
                parseLevel(text(resolve(source, fields.level()))),
                text(resolve(source, fields.traceId())),
                errorCode,
                exceptionType,
                message,
                stackTrace,
                java.util.Map.of());
    }

    /** First match of the pattern (group 1 when present, else the whole match) in the first characters of the text; "" if none. */
    private static String firstMatch(Pattern pattern, String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        Matcher m = pattern.matcher(text.length() > EXCEPTION_SCAN_CHARS ? text.substring(0, EXCEPTION_SCAN_CHARS) : text);
        if (!m.find()) {
            return "";
        }
        String g = m.groupCount() >= 1 ? m.group(1) : m.group();
        return g == null ? "" : g;
    }

    private LogLevel parseLevel(String raw) {
        LogLevel aliased = levelAliases.get(raw.trim().toUpperCase(Locale.ROOT));
        return aliased != null ? aliased : LogLevel.parse(raw);
    }

    private Instant parseTimestamp(String documentId, JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            throw new CorruptLogRecordException(documentId, "timestamp missing");
        }
        if (node.isNumber()) {
            try {
                return numericAsSeconds ? Instant.ofEpochSecond(node.asLong()) : Instant.ofEpochMilli(node.asLong());
            } catch (DateTimeException e) {
                throw new CorruptLogRecordException(documentId, "timestamp unparseable");
            }
        }
        String text = node.asText().trim();
        for (TimestampFormat f : timestampFormats) {
            Instant parsed = f.parse(text);
            if (parsed != null) {
                return parsed;
            }
        }
        throw new CorruptLogRecordException(documentId, "timestamp unparseable");
    }

    /** One configured way to read a textual timestamp; returns null (never throws) when the text does not match. */
    private interface TimestampFormat {
        Instant parse(String text);

        static TimestampFormat of(String spec, ZoneId zone) {
            return switch (spec.trim()) {
                case "iso" -> text -> {
                    try {
                        return Instant.parse(text);
                    } catch (DateTimeException e) {
                        try {
                            return java.time.OffsetDateTime.parse(text).toInstant();
                        } catch (DateTimeException e2) {
                            return null;
                        }
                    }
                };
                case "epoch_millis" -> text -> digits(text) ? safeEpoch(text, false) : null;
                case "epoch_seconds" -> text -> digits(text) ? safeEpoch(text, true) : null;
                default -> {
                    DateTimeFormatter f;
                    try {
                        f = DateTimeFormatter.ofPattern(spec, Locale.ENGLISH).withZone(zone);
                    } catch (IllegalArgumentException e) {
                        throw new IllegalArgumentException(
                                "dpi.elasticsearch.mapping.timestamp-formats contains an invalid pattern: " + spec);
                    }
                    yield text -> {
                        try {
                            return Instant.from(f.parse(text));
                        } catch (DateTimeException e) {
                            return null;
                        }
                    };
                }
            };
        }

        private static boolean digits(String t) {
            return !t.isEmpty() && t.length() <= 19 && t.chars().allMatch(Character::isDigit);
        }

        private static Instant safeEpoch(String t, boolean seconds) {
            try {
                long v = Long.parseLong(t);
                return seconds ? Instant.ofEpochSecond(v) : Instant.ofEpochMilli(v);
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    /** Accepts both flat keys ("log.level" stored literally) and nested objects. */
    static JsonNode resolve(JsonNode root, String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        JsonNode flat = root.get(path);
        if (flat != null) {
            return flat;
        }
        JsonNode current = root;
        for (String part : path.split("\\.")) {
            if (current == null || !current.isObject()) {
                return null;
            }
            current = current.get(part);
        }
        return current;
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "";
        }
        if (node.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode n : node) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(n.asText());
            }
            return sb.toString();
        }
        return node.isValueNode() ? node.asText() : node.toString();
    }
}
