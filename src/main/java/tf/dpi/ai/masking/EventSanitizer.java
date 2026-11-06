package tf.dpi.ai.masking;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import tf.dpi.ai.model.LogEvent;

/**
 * Applies masking to every free-text and identifier field of an event. Message and stack trace are masked (JSON
 * aware for the message); grouping identifiers are replaced by a stable hash if they contain anything sensitive,
 * so distinct values never collapse into one placeholder and grouping stays consistent. Fails closed: any
 * {@link MaskingException} propagates and the event must not be embedded.
 */
public final class EventSanitizer {

    private final MaskingEngine engine;
    private final StructuredLogMasker structured;

    public EventSanitizer(MaskingEngine engine, StructuredLogMasker structured) {
        this.engine = engine;
        this.structured = structured;
    }

    public LogEvent sanitize(LogEvent e) {
        return new LogEvent(
                e.id(),
                e.sourceIndex(),
                e.timestamp(),
                identifier(e.service()),
                identifier(e.host()),
                e.level(),
                identifier(e.traceId()),
                identifier(e.errorCode()),
                identifier(e.exceptionType()),
                structured.maskAuto(e.message()),
                engine.maskText(e.stackTrace()),
                structured.maskAttributes(e.attributes()));
    }

    /** True when sanitizing changed anything (feeds the "logs masked" metric). */
    public boolean wasMasked(LogEvent original, LogEvent sanitized) {
        return !original.message().equals(sanitized.message()) || !original.stackTrace().equals(sanitized.stackTrace())
                || !original.traceId().equals(sanitized.traceId()) || !original.service().equals(sanitized.service())
                || !original.host().equals(sanitized.host()) || !original.errorCode().equals(sanitized.errorCode());
    }

    private String identifier(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String masked = engine.maskText(value);
        if (masked.equals(value)) {
            return value;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return "masked-" + HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException ex) {
            throw new MaskingException("SHA-256 unavailable; content withheld", ex);
        }
    }
}
