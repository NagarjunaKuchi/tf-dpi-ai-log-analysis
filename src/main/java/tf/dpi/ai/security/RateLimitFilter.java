package tf.dpi.ai.security;

import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-principal token bucket (per remote address when unauthenticated). The bucket map is bounded (LRU) so a flood of
 * distinct keys cannot exhaust memory. Limits are per instance, not cluster-wide: with N replicas the effective limit is
 * up to N times higher (documented limitation).
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final int MAX_KEYS = 10_000;

    private final int perMinute;
    private final Map<String, Bucket> buckets = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Bucket> eldest) {
            return size() > MAX_KEYS;
        }
    };

    public RateLimitFilter(int perMinute) {
        if (perMinute < 1) {
            throw new IllegalArgumentException("rate limit must be positive");
        }
        this.perMinute = perMinute;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        String key = a != null && a.isAuthenticated() && !"anonymousUser".equals(a.getName())
                ? "u:" + a.getName() : "ip:" + req.getRemoteAddr();
        Bucket b;
        synchronized (buckets) {
            b = buckets.computeIfAbsent(key, k -> Bucket.builder()
                    .addLimit(l -> l.capacity(perMinute).refillGreedy(perMinute, Duration.ofMinutes(1))).build());
        }
        if (b.tryConsume(1)) {
            chain.doFilter(req, res);
            return;
        }
        res.setStatus(429);
        res.setHeader("Retry-After", "60");
        res.setContentType("application/problem+json");
        res.getWriter().write("{\"title\":\"Too Many Requests\",\"status\":429}");
    }
}
