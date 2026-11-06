package tf.dpi.ai.masking;

import java.util.regex.Pattern;

/**
 * One regex masking rule.
 *
 * @param replacement {@link java.util.regex.Matcher#appendReplacement} syntax; use {@code $1} to keep a key prefix
 */
public record MaskingRule(String name, MaskingCategory category, Pattern pattern, String replacement) {

    public MaskingRule {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("rule name must not be blank");
        }
        if (category == null || pattern == null || replacement == null) {
            throw new IllegalArgumentException("rule '" + name + "' is incomplete");
        }
    }

    public static MaskingRule of(String name, MaskingCategory category, String regex, String replacement) {
        return new MaskingRule(name, category, Pattern.compile(regex), replacement);
    }

    /** Placeholder text for a category. */
    public static String placeholder(MaskingCategory category) {
        return "[MASKED:" + category.name() + "]";
    }
}
