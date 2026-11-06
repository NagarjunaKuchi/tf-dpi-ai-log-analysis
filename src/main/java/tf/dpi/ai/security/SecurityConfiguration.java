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
    SecurityFilterChain api(HttpSecurity http, SecurityProperties props) throws Exception {
        if (props.jwtIssuerUri() == null || props.jwtIssuerUri().isBlank()) {
            throw new IllegalStateException("dpi.security.jwt-issuer-uri (DPI_JWT_ISSUER_URI) must be configured");
        }
        http.csrf(AbstractHttpConfigurer::disable) // stateless bearer-token API, no cookies
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/actuator/**").hasRole("OPERATOR")
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").hasRole("OPERATOR")
                        .requestMatchers(HttpMethod.POST, "/api/v1/analyses").hasAnyRole("ANALYST", "REVIEWER")
                        .requestMatchers(HttpMethod.GET, "/api/v1/analyses/**").hasAnyRole("ANALYST", "REVIEWER")
                        .requestMatchers(HttpMethod.POST, "/api/v1/analyses/*/review").hasRole("REVIEWER")
                        .requestMatchers(HttpMethod.POST, "/api/v1/knowledge").hasRole("KNOWLEDGE_ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/knowledge/*/versions/*/validate").hasRole("REVIEWER")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter(props.rolesClaim()))))
                .addFilterBefore(new CorrelationIdFilter(), BasicAuthenticationFilter.class)
                .addFilterAfter(new RateLimitFilter(props.rateLimitPerMinute()), BasicAuthenticationFilter.class)
                .headers(Customizer.withDefaults());
        return http.build();
    }

    static JwtAuthenticationConverter converter(String claim) {
        JwtAuthenticationConverter c = new JwtAuthenticationConverter();
        c.setJwtGrantedAuthoritiesConverter(jwt -> authorities(jwt, claim));
        return c;
    }

    static Collection<GrantedAuthority> authorities(Jwt jwt, String claim) {
        List<String> roles = jwt.getClaimAsStringList(claim);
        if (roles == null) {
            return List.of();
        }
        return roles.stream().filter(r -> r != null && r.matches("[A-Z_]{2,40}"))
                .<GrantedAuthority>map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList();
    }
}
