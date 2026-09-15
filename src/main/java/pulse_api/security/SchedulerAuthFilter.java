package pulse_api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Authenticates /internal/jobs/** callers as ROLE_SCHEDULER (contract §4): a Google OIDC token
 * with aud == PULSE_API_BASE_URL and email == SCHEDULER_SA_EMAIL, or — local dev only — the
 * X-Job-Token header equal to JOBS_TOKEN (constant-time compare). Anything else stays
 * unauthenticated and the endpoint rules answer 401.
 */
@Slf4j
@Component
public class SchedulerAuthFilter extends OncePerRequestFilter {

    public static final String ROLE_SCHEDULER = "SCHEDULER";
    static final String JOB_TOKEN_HEADER = "X-Job-Token";

    private final IdTokenVerifier verifier;
    private final String audience;
    private final String schedulerEmail;
    private final String jobsToken;

    public SchedulerAuthFilter(IdTokenVerifier verifier,
                               @Value("${app.jobs.base-url:}") String audience,
                               @Value("${app.jobs.scheduler-sa-email:}") String schedulerEmail,
                               @Value("${app.jobs.token:}") String jobsToken) {
        this.verifier = verifier;
        this.audience = audience;
        this.schedulerEmail = schedulerEmail;
        this.jobsToken = jobsToken;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/internal/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String principal = authenticate(request);
        if (principal != null) {
            var auth = new UsernamePasswordAuthenticationToken(
                    principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + ROLE_SCHEDULER)));
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
        chain.doFilter(request, response);
    }

    private String authenticate(HttpServletRequest request) {
        String jobToken = request.getHeader(JOB_TOKEN_HEADER);
        if (jobToken != null) {
            if (!jobsToken.isBlank() && MessageDigest.isEqual(
                    jobsToken.getBytes(StandardCharsets.UTF_8), jobToken.getBytes(StandardCharsets.UTF_8))) {
                return "jobs-token";
            }
            log.warn("AUDIT job trigger rejected: bad X-Job-Token");
            return null;
        }
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return null;
        }
        if (audience.isBlank() || schedulerEmail.isBlank()) {
            log.warn("AUDIT job trigger rejected: PULSE_API_BASE_URL / SCHEDULER_SA_EMAIL not configured");
            return null;
        }
        return verifier.verify(header.substring(7), audience)
                .filter(id -> id.email() != null && id.email().equalsIgnoreCase(schedulerEmail))
                .map(IdTokenVerifier.VerifiedIdentity::email)
                .orElseGet(() -> {
                    log.warn("AUDIT job trigger rejected: OIDC token not from the scheduler service account");
                    return null;
                });
    }
}
