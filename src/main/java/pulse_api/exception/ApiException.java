package pulse_api.exception;

import org.springframework.http.HttpStatus;

/**
 * An error with a chosen HTTP status and optional machine-readable code, rendered by
 * {@link GlobalExceptionHandler} as {@code {status:"error", message, code?}} (contract §0.3).
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String message) {
        this(status, message, null);
    }

    public ApiException(HttpStatus status, String message, String code) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static ApiException notConfigured(String what) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, what + " is not configured", "NOT_CONFIGURED");
    }

    public static ApiException upstream(String message) {
        return new ApiException(HttpStatus.BAD_GATEWAY, message, "UPSTREAM_FAILED");
    }
}
