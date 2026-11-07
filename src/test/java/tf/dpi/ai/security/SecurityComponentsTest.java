package tf.dpi.ai.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.Jwt;

class SecurityComponentsTest {

    private static Jwt jwt(Map<String, Object> claims) {
        return new Jwt("t", Instant.now(), Instant.now().plusSeconds(60), Map.of("alg", "none"), claims);
    }

    @Test
    void rolesAreMappedAndMalformedRolesDropped() {
        var auth = SecurityConfiguration.authorities(jwt(Map.of("roles", List.of("REVIEWER", "bad role!", "ANALYST"))), "roles", "dpi-log-analysis");
        assertThat(auth).extracting(Object::toString).containsExactlyInAnyOrder("ROLE_REVIEWER", "ROLE_ANALYST");
    }

    @Test
    void missingRolesClaimGrantsNothing() {
        assertThat(SecurityConfiguration.authorities(jwt(Map.of("sub", "x")), "roles", "dpi-log-analysis")).isEmpty();
    }

    @Test
    void rateLimitReturns429AfterBudgetAndIsPerClient() throws Exception {
        RateLimitFilter f = new RateLimitFilter(2);
        FilterChain chain = (rq, rs) -> { };
        int[] codes = new int[3];
        for (int i = 0; i < 3; i++) {
            MockHttpServletRequest rq = new MockHttpServletRequest("GET", "/api/v1/analyses/x");
            rq.setRemoteAddr("10.0.0.1");
            MockHttpServletResponse rs = new MockHttpServletResponse();
            f.doFilter(rq, rs, chain);
            codes[i] = rs.getStatus();
        }
        assertThat(codes).containsExactly(200, 200, 429);
        MockHttpServletRequest other = new MockHttpServletRequest("GET", "/api/v1/analyses/x");
        other.setRemoteAddr("10.0.0.2");
        MockHttpServletResponse rs = new MockHttpServletResponse();
        f.doFilter(other, rs, chain);
        assertThat(rs.getStatus()).isEqualTo(200);
    }

    @Test
    void correlationIdIsEchoedWhenSafeAndReplacedWhenNot() throws Exception {
        CorrelationIdFilter f = new CorrelationIdFilter();
        MockHttpServletRequest ok = new MockHttpServletRequest();
        ok.addHeader(CorrelationIdFilter.HEADER, "abc-12345678");
        MockHttpServletResponse r1 = new MockHttpServletResponse();
        String[] seen = new String[1];
        f.doFilter(ok, r1, (rq, rs) -> seen[0] = MDC.get(CorrelationIdFilter.MDC_KEY));
        assertThat(r1.getHeader(CorrelationIdFilter.HEADER)).isEqualTo("abc-12345678");
        assertThat(seen[0]).isEqualTo("abc-12345678");
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();

        MockHttpServletRequest evil = new MockHttpServletRequest();
        evil.addHeader(CorrelationIdFilter.HEADER, "x\r\nSet-Cookie: a=b");
        MockHttpServletResponse r2 = new MockHttpServletResponse();
        f.doFilter(evil, r2, (rq, rs) -> { });
        assertThat(r2.getHeader(CorrelationIdFilter.HEADER)).matches("[0-9a-f\\-]{36}");
    }
}
