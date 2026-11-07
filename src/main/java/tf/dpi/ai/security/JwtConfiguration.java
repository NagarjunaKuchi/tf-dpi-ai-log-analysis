package tf.dpi.ai.security;

import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;

/**
 * Token validation: signature (RS256 only, so {@code none} and HMAC "algorithm confusion" tokens are rejected), expiry/not-before,
 * exact issuer, required audience and a non-blank subject. The decoder is created lazily so the service can start while the
 * identity provider is still starting; the first request that needs keys triggers discovery.
 */
@Configuration
class JwtConfiguration {

    @Bean
    JwtDecoder jwtDecoder(SecurityProperties p) {
        requireIssuer(p);
        return new SupplierJwtDecoder(() -> {
            NimbusJwtDecoder decoder = p.jwkSetUri().isBlank()
                    ? NimbusJwtDecoder.withIssuerLocation(p.jwtIssuerUri()).jwsAlgorithm(SignatureAlgorithm.RS256).build()
                    : NimbusJwtDecoder.withJwkSetUri(p.jwkSetUri()).jwsAlgorithm(SignatureAlgorithm.RS256).build();
            decoder.setJwtValidator(validator(p));
            return decoder;
        });
    }

    static void requireIssuer(SecurityProperties p) {
        if (p.jwtIssuerUri() == null || p.jwtIssuerUri().isBlank()) {
            throw new IllegalStateException("dpi.security.jwt-issuer-uri (DPI_JWT_ISSUER_URI) must be configured");
        }
    }

    static OAuth2TokenValidator<Jwt> validator(SecurityProperties p) {
        List<OAuth2TokenValidator<Jwt>> all = new ArrayList<>();
        all.add(JwtValidators.createDefaultWithIssuer(p.jwtIssuerUri())); // exp / nbf (60 s skew) + exact iss
        all.add(new JwtClaimValidator<String>("sub", s -> s != null && !s.isBlank()));
        if (p.audience() != null && !p.audience().isBlank()) {
            all.add(new JwtClaimValidator<List<String>>("aud", aud -> aud != null && aud.contains(p.audience())));
        }
        return new DelegatingOAuth2TokenValidator<>(all);
    }
}
