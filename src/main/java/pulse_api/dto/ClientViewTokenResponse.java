package pulse_api.dto;

/** Raw token is returned exactly once; the server keeps only its SHA-256. */
public record ClientViewTokenResponse(String token, String url) {}
