package pulse_api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import pulse_api.exception.ErrorResponse;

import java.io.IOException;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fixed-window rate limiting per client IP (spec §7): auth 10/min, actions 20/min, Ask 10/min,
 * public client view 60/min. Windows live in process memory, so this is only correct while the
 * service runs at --max-instances=1 (same trade-off as bookr-api).
 */
@Slf4j
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    static final long WINDOW_MILLIS = 60_000;
    static final int AUTH_LIMIT = 10;
    static final int ACTIONS_LIMIT = 20;
    static final int ASK_LIMIT = 10;
    static final int PUBLIC_LIMIT = 60;
    private static final int MAX_TRACKED_KEYS = 50_000;

    private record Window(long startMillis, AtomicInteger count) {}

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final ClientIpResolver clientIps;
    private final Clock clock;

    public RateLimitFilter(ClientIpResolver clientIps, Clock clock) {
        this.clientIps = clientIps;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String bucket = bucketFor(request);
        if (bucket != null) {
            String ip = clientIps.resolve(request);
            if (!allow("ip:" + ip + ":" + bucket, limitFor(bucket))) {
                log.warn("AUDIT rate limit exceeded ip='{}' path='{}'", ip, request.getRequestURI());
                response.setStatus(429);
                response.setContentType("application/json");
                response.getWriter().write(ErrorResponse.of(
                        "Too many requests. Please slow down and try again shortly.", "RATE_LIMITED").toJson());
                return;
            }
        }
        chain.doFilter(request, response);
    }

    /** Null when the path is not rate-limited. */
    static String bucketFor(HttpServletRequest request) {
        String path = request.getRequestURI();
        boolean post = "POST".equals(request.getMethod());
        if (path.startsWith("/api/auth/") && post) {
            return "auth";
        }
        if (path.startsWith("/api/actions/") && post) {
            return "actions";
        }
        if (path.equals("/api/ask") && post) {
            return "ask";
        }
        if (path.startsWith("/api/public/")) {
            return "public";
        }
        return null;
    }

    static int limitFor(String bucket) {
        return switch (bucket) {
            case "auth" -> AUTH_LIMIT;
            case "actions" -> ACTIONS_LIMIT;
            case "ask" -> ASK_LIMIT;
            default -> PUBLIC_LIMIT;
        };
    }

    boolean allow(String key, int limit) {
        long now = clock.millis();
        Window window = windows.compute(key, (k, existing) ->
                existing == null || now - existing.startMillis() >= WINDOW_MILLIS
                        ? new Window(now, new AtomicInteger(0))
                        : existing);
        if (windows.size() > MAX_TRACKED_KEYS) {
            windows.entrySet().removeIf(e -> now - e.getValue().startMillis() >= WINDOW_MILLIS);
        }
        return window.count().incrementAndGet() <= limit;
    }
}
