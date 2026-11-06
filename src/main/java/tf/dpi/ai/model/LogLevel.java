package tf.dpi.ai.model;

import java.util.Locale;

/** Log severity, ordered from least to most severe. */
public enum LogLevel {
    TRACE, DEBUG, INFO, WARN, ERROR, FATAL;

    /** Parses common level spellings; unknown or blank values map to INFO. */
    public static LogLevel parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return INFO;
        }
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "TRACE", "FINEST", "FINER" -> TRACE;
            case "DEBUG", "FINE" -> DEBUG;
            case "WARN", "WARNING" -> WARN;
            case "ERROR", "SEVERE" -> ERROR;
            case "FATAL", "CRITICAL", "EMERGENCY", "ALERT" -> FATAL;
            default -> INFO;
        };
    }

    public boolean atLeast(LogLevel other) {
        return this.compareTo(other) >= 0;
    }
}
