package tf.dpi.ai.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/** Real signed tokens through the same validator chain the service uses (signature, expiry, issuer, audience, subject). */
class JwtValidationTest {

    static final String ISS = "https://idp.example.org/realms/dpi";
    static final SecurityProperties PROPS = new SecurityProperties(ISS, "", "dpi-log-analysis", 60, "roles");
    static KeyPair key;
    static KeyPair otherKey;
    static NimbusJwtDecoder decoder;

    @BeforeAll
    static void init() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        key = g.generateKeyPair();
        otherKey = g.generateKeyPair();
        decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) key.getPublic()).build();
        decoder.setJwtValidator(JwtConfiguration.validator(PROPS));
    }

    private static JWTClaimsSet.Builder claims() {
        return new JWTClaimsSet.Builder().issuer(ISS).subject("alice").audience("dpi-log-analysis")
                .expirationTime(Date.from(Instant.now().plusSeconds(600))).issueTime(new Date());
    }

    private static String sign(JWTClaimsSet c, KeyPair k) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), c);
        jwt.sign(new RSASSASigner(k.getPrivate()));
        return jwt.serialize();
    }

    @Test
    void validTokenIsAccepted() throws Exception {
        Jwt jwt = decoder.decode(sign(claims().build(), key));
        assertThat(jwt.getSubject()).isEqualTo("alice");
    }

    @Test
    void rejectsExpiredWrongIssuerWrongAudienceMissingSubject() throws Exception {
        String expired = sign(claims().expirationTime(Date.from(Instant.now().minusSeconds(600))).build(), key);
        String wrongIss = sign(claims().issuer("https://evil.example.org").build(), key);
        String wrongAud = sign(claims().audience("account").build(), key);
        String noAud = sign(new JWTClaimsSet.Builder().issuer(ISS).subject("a")
                .expirationTime(Date.from(Instant.now().plusSeconds(60))).build(), key);
        String noSub = sign(new JWTClaimsSet.Builder().issuer(ISS).audience("dpi-log-analysis")
                .expirationTime(Date.from(Instant.now().plusSeconds(60))).build(), key);
        for (String t : List.of(expired, wrongIss, wrongAud, noAud, noSub)) {
            assertThatThrownBy(() -> decoder.decode(t)).isInstanceOf(JwtException.class);
        }
    }

    @Test
    void rejectsTokenSignedWithAnotherKey() throws Exception {
        assertThatThrownBy(() -> decoder.decode(sign(claims().build(), otherKey))).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsAlgNoneAndHmacConfusionUsingThePublicKeyAsSecret() throws Exception {
        String none = new PlainJWT(claims().build()).serialize();
        SignedJWT hs = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims().build());
        hs.sign(new MACSigner(key.getPublic().getEncoded().length >= 32 ? java.util.Arrays.copyOf(key.getPublic().getEncoded(), 64)
                : new byte[64]));
        for (String t : List.of(none, hs.serialize())) {
            assertThatThrownBy(() -> decoder.decode(t)).isInstanceOf(JwtException.class);
        }
    }

    @Test
    void blankAudienceConfigurationDisablesOnlyTheAudienceCheck() throws Exception {
        NimbusJwtDecoder lax = NimbusJwtDecoder.withPublicKey((RSAPublicKey) key.getPublic()).build();
        lax.setJwtValidator(JwtConfiguration.validator(new SecurityProperties(ISS, "", "", 60, "roles")));
        assertThat(lax.decode(sign(claims().audience("account").build(), key)).getSubject()).isEqualTo("alice");
        assertThatThrownBy(() -> lax.decode(sign(claims().issuer("x").build(), key))).isInstanceOf(JwtException.class);
    }

    @Test
    void keycloakRoleLayoutsAreRecognisedAndForeignRolesIgnored() throws Exception {
        Jwt jwt = decoder.decode(sign(claims()
                .claim("realm_access", Map.of("roles", List.of("REVIEWER", "offline_access", "uma_authorization")))
                .claim("resource_access", Map.of("dpi-log-analysis", Map.of("roles", List.of("KNOWLEDGE_ADMIN")),
                        "other-client", Map.of("roles", List.of("OPERATOR"))))
                .claim("roles", List.of("ANALYST")).build(), key));
        assertThat(SecurityConfiguration.authorities(jwt, "roles", "dpi-log-analysis")).extracting(Object::toString)
                .containsExactlyInAnyOrder("ROLE_ANALYST", "ROLE_REVIEWER", "ROLE_KNOWLEDGE_ADMIN");
    }
}
