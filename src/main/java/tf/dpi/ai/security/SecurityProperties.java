package tf.dpi.ai.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** @param rolesClaim JWT claim holding a string array of roles (ANALYST, REVIEWER, KNOWLEDGE_ADMIN) */
@ConfigurationProperties("dpi.security")
public record SecurityProperties(
        @DefaultValue("") String jwtIssuerUri,
        @DefaultValue("60") int rateLimitPerMinute,
        @DefaultValue("roles") String rolesClaim) {
}
