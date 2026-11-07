package tf.dpi.ai.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Named failure signatures (regular expressions over log text). They never limit what can be analysed:
 * <ul>
 *   <li>they are <b>hints</b> given to the model ("these signatures appear in the current evidence");</li>
 *   <li>a finding whose category equals a signature name is accepted only if that signature matches the current evidence it
 *       cites, which stops a mislabelled claim such as an OOM verdict for a connection error;</li>
 *   <li>a finding with any other category is checked by the generic rules only (cited evidence, current observation, quoted
 *       terms found verbatim).</li>
 * </ul>
 * The built-in set covers common infrastructure failures; add or replace entries with {@code dpi.agent.signatures.custom}.
 */
public final class FailureSignatures {

    public static final String OTHER = "OTHER";
    private static final int MAX_CATEGORY_CHARS = 48;

    /** A configured signature. */
    public record Definition(String name, String pattern) {
    }

    private final Map<String, Pattern> byName;

    private FailureSignatures(Map<String, Pattern> byName) {
        this.byName = Map.copyOf(byName);
        this.order = List.copyOf(byName.keySet());
    }

    private final List<String> order;

    public static FailureSignatures builtIn() {
        return of(true, List.of());
    }

    /**
     * @param includeBuiltIn start from the built-in signatures
     * @param custom         added to, or replacing by name, the built-ins. Names are normalised to UPPER_SNAKE_CASE.
     * @throws IllegalArgumentException for an invalid name or regular expression (the message never echoes the pattern)
     */
    public static FailureSignatures of(boolean includeBuiltIn, List<Definition> custom) {
        Map<String, Pattern> map = new LinkedHashMap<>();
        if (includeBuiltIn) {
            for (Definition d : BUILT_IN) {
                map.put(d.name(), Pattern.compile(d.pattern()));
            }
        }
        for (Definition d : custom == null ? List.<Definition>of() : custom) {
            String name = normalizeCategory(d.name());
            if (OTHER.equals(name) || d.name() == null || d.name().isBlank()) {
                throw new IllegalArgumentException("dpi.agent.signatures.custom: name must be set and must not be OTHER");
            }
            if (d.pattern() == null || d.pattern().isBlank()) {
                throw new IllegalArgumentException("dpi.agent.signatures.custom: pattern for '" + name + "' must be set");
            }
            try {
                map.put(name, Pattern.compile(d.pattern()));
            } catch (PatternSyntaxException e) {
                throw new IllegalArgumentException("dpi.agent.signatures.custom: pattern for '" + name + "' is not a valid regular expression");
            }
        }
        return new FailureSignatures(map);
    }

    /** Signature names whose pattern matches the text, in definition order. */
    public List<String> detect(CharSequence text) {
        List<String> hits = new ArrayList<>();
        for (String name : order) {
            if (byName.get(name).matcher(text).find()) {
                hits.add(name);
            }
        }
        return hits;
    }

    public boolean isKnown(String normalizedCategory) {
        return byName.containsKey(normalizedCategory);
    }

    /** True when the named signature matches; false for an unknown name. */
    public boolean matches(String normalizedCategory, CharSequence text) {
        Pattern p = byName.get(normalizedCategory);
        return p != null && p.matcher(text).find();
    }

    public List<String> names() {
        return order;
    }

    /**
     * Model output is untrusted and ends up in the report: keep only UPPER_SNAKE_CASE, at most 48 characters;
     * anything empty becomes {@link #OTHER}.
     */
    public static String normalizeCategory(String raw) {
        if (raw == null) {
            return OTHER;
        }
        String s = raw.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (s.length() > MAX_CATEGORY_CHARS) {
            s = s.substring(0, MAX_CATEGORY_CHARS).replaceAll("_+$", "");
        }
        return s.isEmpty() ? OTHER : s;
    }

    private static final List<Definition> BUILT_IN = List.of(
            new Definition("DB_CONNECTION_REFUSED",
                    "(?i)connection (to .{0,80})?refused|ECONNREFUSED|could not (open|obtain) (a )?(jdbc )?connection"
                            + "|CannotGetJdbcConnectionException|PSQLException.{0,80}connection|Connection is not available"
                            + "|HikariPool.{0,60}(timeout|unavailable)"),
            new Definition("OUT_OF_MEMORY",
                    "(?i)java\\.lang\\.OutOfMemoryError|OutOfMemoryError|GC overhead limit exceeded|Java heap space"
                            + "|Metaspace|unable to create (new )?native thread"),
            new Definition("ACTIVEMQ_ISSUE",
                    "(?i)activemq|javax\\.jms\\.|jakarta\\.jms\\.|JMSException|failover:\\(|Broker .{0,40}(unavailable|stopped)"
                            + "|Channel was inactive|ConnectionFactory.{0,40}(fail|refused)"),
            new Definition("UPSTREAM_CONNECTION_RESET",
                    "(?i)upstream connect error|disconnect/reset before headers|reset reason|no healthy upstream"
                            + "|connection (reset|termination)"),
            new Definition("TIMEOUT",
                    "(?i)SocketTimeoutException|ReadTimeoutException|ConnectTimeoutException|read timed out|connect timed out"
                            + "|TimeoutException|deadline exceeded|gateway time-?out"),
            new Definition("DNS_RESOLUTION",
                    "(?i)UnknownHostException|name or service not known|no such host|temporary failure in name resolution"),
            new Definition("DISK_FULL",
                    "(?i)no space left on device|disk quota exceeded|ENOSPC|not enough space on (the )?disk"),
            new Definition("TLS_FAILURE",
                    "(?i)SSLHandshakeException|SSLPeerUnverifiedException|PKIX path|unable to find valid certification path"
                            + "|CertificateExpiredException|certificate (has )?expired"),
            new Definition("AUTH_FAILURE",
                    "(?i)AccessDeniedException|AuthenticationException|authentication failed|invalid[_ ]token"
                            + "|\\bunauthorized\\b|\\bforbidden\\b"),
            new Definition("NULL_POINTER", "NullPointerException"));
}
