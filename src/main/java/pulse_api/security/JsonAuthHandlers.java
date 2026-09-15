package pulse_api.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import pulse_api.exception.ErrorResponse;

import java.io.IOException;

/** 401 / 403 as the contract's JSON error shape instead of Spring's defaults. */
@Component
public class JsonAuthHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException e)
            throws IOException {
        write(response, 401, ErrorResponse.of("Authentication required", "UNAUTHENTICATED"));
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException e)
            throws IOException {
        write(response, 403, ErrorResponse.of("Forbidden", "FORBIDDEN"));
    }

    private static void write(HttpServletResponse response, int status, ErrorResponse body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write(body.toJson());
    }
}
