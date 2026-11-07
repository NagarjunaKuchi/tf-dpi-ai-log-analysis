package tf.dpi.ai.security;

import java.util.Collection;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;

/**
 * Stateless JWT resource server with role-based access. Fail-closed: without a configured issuer the application refuses
 * to start rather than serving an open API. Roles: ANALYST (submit/read analyses), REVIEWER (decide on reports, validate
 * knowledge), KNOWLEDGE_ADMIN (ingest runbooks / incidents).
 */
@Configuration
@EnableConfigurationProperties(SecurityProperties.class)
class SecurityConfiguration {

    @Bean
    SecurityFilterChain api(HttpSecurity http, SecurityProperties props,
            @org.springframework.beans.factory.annotation.Value("${management.server.port:-1}") int managementPort,
            @org.springframework.beans.factory.annotation.Value("${server.port:8080}") int serverPort) throws Exception {
        JwtConfiguration.requireIssuer(props);
        http.csrf(AbstractHttpConfigurer::disable) // stateless bearer-token API, no cookies
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        // Prometheus scrape: only on the dedicated management port, which must be NetworkPolicy-restricted
                        .requestMatchers(r -> managementPort > 0 && managementPort != serverPort
                                && r.getLocalPort() == managementPort && r.getRequestURI().startsWith("/actuator/prometheus"))
                        .permitAll()
                        .requestMatchers("/actuator/**").hasRole("OPERATOR")
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").hasRole("OPERATOR")
                        .requestMatchers(HttpMethod.POST, "/api/v1/analyses").hasAnyRole("ANALYST", "REVIEWER")
                        .requestMatchers(HttpMethod.GET, "/api/v1/analyses/**").hasAnyRole("ANALYST", "REVIEWER")
                        .requestMatchers(HttpMethod.POST, "/api/v1/analyses/*/review").hasRole("REVIEWER")
                        .requestMatchers(HttpMethod.POST, "/api/v1/knowledge").hasRole("KNOWLEDGE_ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/knowledge/*/versions/*/validate").hasRole("REVIEWER")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter(props.rolesClaim(), props.audience()))))
                .addFilterBefore(new CorrelationIdFilter(), BasicAuthenticationFilter.class)
                .addFilterAfter(new RateLimitFilter(props.rateLimitPerMinute()), BasicAuthenticationFilter.class)
                .headers(Customizer.withDefaults());
        return http.build();
    }

    static JwtAuthenticationConverter converter(String claim, String clientId) {
        JwtAuthenticationConverter c = new JwtAuthenticationConverter();
        c.setJwtGrantedAuthoritiesConverter(jwt -> authorities(jwt, claim, clientId));
        return c;
    }

    private static final java.util.regex.Pattern ROLE = java.util.regex.Pattern.compile("[A-Z_]{2,40}");

    /**
     * Roles from the top-level claim plus Keycloak's {@code realm_access.roles} and {@code resource_access.<client>.roles}.
     * Only upper-case identifiers are accepted, so unrelated Keycloak roles (offline_access, uma_authorization, ...) are ignored.
     */
    static Collection<GrantedAuthority> authorities(Jwt jwt, String claim, String clientId) {
        java.util.Set<String> roles = new java.util.LinkedHashSet<>();
        addAll(roles, jwt.getClaimAsStringList(claim));
        addAll(roles, rolesIn(jwt.getClaimAsMap("realm_access")));
        if (clientId != null && !clientId.isBlank() && jwt.getClaimAsMap("resource_access") != null
                && jwt.getClaimAsMap("resource_access").get(clientId) instanceof java.util.Map<?, ?> m) {
            addAll(roles, rolesIn(m));
        }
        return roles.stream().<GrantedAuthority>map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList();
    }

    private static List<String> rolesIn(java.util.Map<?, ?> m) {
        return m != null && m.get("roles") instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
    }

    private static void addAll(java.util.Set<String> out, List<String> in) {
        if (in != null) {
            in.stream().filter(r -> r != null && ROLE.matcher(r).matches()).forEach(out::add);
        }
    }
}
