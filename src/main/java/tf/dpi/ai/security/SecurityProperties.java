package tf.dpi.ai.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param jwtIssuerUri     expected {@code iss} of every token (required; the service refuses to start without it)
 * @param jwkSetUri        optional key-set URL. Set it when the service reaches the identity provider under a different
 *                         address than the one that appears in {@code iss} (typical in containers); otherwise keys are
 *                         discovered from the issuer
 * @param audience         required {@code aud} value; blank disables the check (not recommended)
 * @param rolesClaim       top-level claim holding a string array of roles; Keycloak's {@code realm_access.roles} and
 *                         {@code resource_access.<audience>.roles} are always read as well
 */
@ConfigurationProperties("dpi.security")
public record SecurityProperties(
        @DefaultValue("") String jwtIssuerUri,
        @DefaultValue("") String jwkSetUri,
        @DefaultValue("dpi-log-analysis") String audience,
        @DefaultValue("60") int rateLimitPerMinute,
        @DefaultValue("roles") String rolesClaim) {
}
