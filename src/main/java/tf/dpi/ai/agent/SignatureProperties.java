package tf.dpi.ai.agent;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Failure signatures used as hints and as a guard against mislabelled findings (see {@link FailureSignatures}).
 * They do not restrict which errors can be analysed.
 *
 * @param builtin include the built-in signatures
 * @param custom  extra signatures; an entry with a built-in's name replaces it
 */
@ConfigurationProperties("dpi.agent.signatures")
public record SignatureProperties(
        @DefaultValue("true") boolean builtin,
        @DefaultValue List<FailureSignatures.Definition> custom) {

    public FailureSignatures toSignatures() {
        return FailureSignatures.of(builtin, custom);
    }
}
