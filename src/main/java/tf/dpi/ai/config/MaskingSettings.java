package tf.dpi.ai.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import tf.dpi.ai.masking.MaskingCategory;

/** Operator-configurable masking policy additions (on top of the built-in rules, which cannot be disabled). */
@ConfigurationProperties("dpi.masking")
public record MaskingSettings(
        @DefaultValue("2000000") int maxInputChars,
        @DefaultValue("2s") Duration maxDuration,
        @DefaultValue List<String> additionalSensitiveJsonKeys,
        @DefaultValue List<CustomRule> customRules) {

    public record CustomRule(String name, @DefaultValue("CUSTOM") MaskingCategory category, String pattern,
            @DefaultValue("[MASKED:CUSTOM]") String replacement) {
    }
}
