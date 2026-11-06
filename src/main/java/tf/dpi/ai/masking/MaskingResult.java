package tf.dpi.ai.masking;

import java.util.EnumMap;
import java.util.Map;

/** Masked text plus per-category replacement counts (feeds the "logs masked" metric and audit). */
public record MaskingResult(String text, Map<MaskingCategory, Integer> counts) {

    public MaskingResult {
        counts = counts.isEmpty() ? Map.of() : Map.copyOf(new EnumMap<>(counts));
    }

    public int totalReplacements() {
        return counts.values().stream().mapToInt(Integer::intValue).sum();
    }

    public static MaskingResult unchanged(String text) {
        return new MaskingResult(text, Map.of());
    }
}
