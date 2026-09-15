package pulse_api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Puts a request id on the MDC so every log line carries it (logging.pattern.level). On Cloud Run
 * the trace id from X-Cloud-Trace-Context is reused so log lines link to the request's own trace.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String MDC_KEY = "requestId";
    public static final String HEADER = "X-Request-Id";
    private static final String CLOUD_TRACE_HEADER = "X-Cloud-Trace-Context";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = resolve(request);
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private String resolve(HttpServletRequest request) {
        String incoming = clean(request.getHeader(HEADER));
        if (incoming != null) {
            return incoming;
        }
        String trace = clean(request.getHeader(CLOUD_TRACE_HEADER));
        if (trace != null) {
            int slash = trace.indexOf('/');
            return slash > 0 ? trace.substring(0, slash) : trace;
        }
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String clean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.matches("[A-Za-z0-9_-]{8,64}") ? trimmed : null;
    }
}
