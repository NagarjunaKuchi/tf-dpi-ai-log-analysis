package tf.dpi.ai.masking;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A complete masking policy: ordered rules, residual detectors used to verify the result (fail closed),
 * sensitive JSON key names, and safety limits.
 *
 * @param residualDetectors patterns that must NOT match after masking; a match means masking is unsafe
 * @param sensitiveJsonKeys lower-case JSON field names whose values are always masked wholesale
 */
public record MaskingPolicy(
        List<MaskingRule> rules,
        List<Pattern> residualDetectors,
        Set<String> sensitiveJsonKeys,
        int maxInputChars,
        Duration maxDuration) {

    public MaskingPolicy {
        rules = List.copyOf(rules);
        residualDetectors = List.copyOf(residualDetectors);
        sensitiveJsonKeys = Set.copyOf(sensitiveJsonKeys);
        if (maxInputChars <= 0) {
            throw new IllegalArgumentException("maxInputChars must be positive");
        }
        if (maxDuration == null || maxDuration.isZero() || maxDuration.isNegative()) {
            throw new IllegalArgumentException("maxDuration must be positive");
        }
    }

    /**
     * Default policy. Assumptions (not defined in the presentation, see docs/ASSUMPTIONS.md):
     * MOSIP UIN = 10 digits, VID = 16 digits; any bare 10- or 16-digit number is masked as NATIONAL_ID,
     * which also masks some unix-second timestamps and phone numbers (deliberately over-masking).
     */
    public static MaskingPolicy defaults() {
        return new MaskingPolicy(
                DefaultMaskingRules.rules(),
                DefaultMaskingRules.residualDetectors(),
                DefaultMaskingRules.sensitiveJsonKeys(),
                2_000_000,
                Duration.ofSeconds(2));
    }

    /** Returns a copy with extra operator-configured rules appended after the built-in ones. */
    public MaskingPolicy withAdditionalRules(List<MaskingRule> extra) {
        List<MaskingRule> all = new ArrayList<>(rules);
        all.addAll(extra);
        return new MaskingPolicy(all, residualDetectors, sensitiveJsonKeys, maxInputChars, maxDuration);
    }

    public MaskingPolicy withAdditionalSensitiveJsonKeys(Set<String> extra) {
        Set<String> all = new java.util.HashSet<>(sensitiveJsonKeys);
        for (String k : extra) {
            all.add(k.toLowerCase(java.util.Locale.ROOT));
        }
        return new MaskingPolicy(rules, residualDetectors, all, maxInputChars, maxDuration);
    }

    public MaskingPolicy withLimits(int maxInputChars, Duration maxDuration) {
        return new MaskingPolicy(rules, residualDetectors, sensitiveJsonKeys, maxInputChars, maxDuration);
    }
}
