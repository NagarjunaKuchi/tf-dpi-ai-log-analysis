package tf.dpi.ai.agent;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Deterministic failure signatures for the scenarios named in the approved design. A finding labelled with one of these
 * categories is accepted only when a signature matches text from the evidence it cites.
 */
public enum Scenario {

    DB_CONNECTION_REFUSED(
            "(?i)connection (to .{0,80})?refused|ECONNREFUSED|could not (open|obtain) (a )?(jdbc )?connection"
                    + "|CannotGetJdbcConnectionException|PSQLException.{0,80}connection|Connection is not available"
                    + "|HikariPool.{0,60}(timeout|unavailable)"),
    OUT_OF_MEMORY("(?i)java\\.lang\\.OutOfMemoryError|OutOfMemoryError|GC overhead limit exceeded|Java heap space"
            + "|Metaspace|unable to create (new )?native thread"),
    ACTIVEMQ_ISSUE("(?i)activemq|javax\\.jms\\.|jakarta\\.jms\\.|JMSException|failover:\\(|Broker .{0,40}(unavailable|stopped)"
            + "|Channel was inactive|ConnectionFactory.{0,40}(fail|refused)");

    private final Pattern pattern;

    Scenario(String regex) {
        this.pattern = Pattern.compile(regex);
    }

    public boolean matches(CharSequence text) {
        return pattern.matcher(text).find();
    }

    /** Scenarios whose signature appears in the text. */
    public static List<Scenario> detect(CharSequence text) {
        return java.util.Arrays.stream(values()).filter(s -> s.matches(text)).toList();
    }

    public static Scenario fromCategory(String category) {
        if (category == null) {
            return null;
        }
        for (Scenario s : values()) {
            if (s.name().equalsIgnoreCase(category.trim())) {
                return s;
            }
        }
        return null;
    }
}
