package pulse_api.security;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestWrapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The one place that decides "which IP address made this request". Cloud Run APPENDS the
 * connecting client's IP to X-Forwarded-For, so the entry {@code trustedProxyHops} from the
 * right is the one a caller cannot forge. Spring's ForwardedHeaderFilter (forward-headers-
 * strategy=framework) hides the raw header, so the request is unwrapped to the container's own.
 */
@Component
public class ClientIpResolver {

    static final String X_FORWARDED_FOR = "X-Forwarded-For";

    private final int trustedProxyHops;

    public ClientIpResolver(@Value("${app.rate-limit.trusted-proxy-hops:1}") int trustedProxyHops) {
        this.trustedProxyHops = Math.max(0, trustedProxyHops);
    }

    public String resolve(HttpServletRequest request) {
        HttpServletRequest raw = unwrap(request);
        String forwarded = raw.getHeader(X_FORWARDED_FOR);
        if (trustedProxyHops == 0 || forwarded == null || forwarded.isBlank()) {
            return raw.getRemoteAddr();
        }
        String[] hops = forwarded.split(",");
        int index = Math.max(0, hops.length - trustedProxyHops);
        String candidate = hops[index].trim();
        return candidate.isEmpty() ? raw.getRemoteAddr() : candidate;
    }

    private static HttpServletRequest unwrap(HttpServletRequest request) {
        ServletRequest current = request;
        while (current instanceof ServletRequestWrapper wrapper) {
            current = wrapper.getRequest();
        }
        return current instanceof HttpServletRequest http ? http : request;
    }
}
