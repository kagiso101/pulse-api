package pulse_api.exception;

import com.fasterxml.jackson.annotation.JsonInclude;

/** The one error shape every endpoint returns (contract §0.3). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(String status, String message, String code) {

    public static ErrorResponse of(String message) {
        return new ErrorResponse("error", message, null);
    }

    public static ErrorResponse of(String message, String code) {
        return new ErrorResponse("error", message, code);
    }

    /** Hand-rolled JSON for filters that reject before Spring MVC is involved. */
    public String toJson() {
        StringBuilder b = new StringBuilder("{\"status\":\"error\",\"message\":\"").append(escape(message)).append('"');
        if (code != null) {
            b.append(",\"code\":\"").append(escape(code)).append('"');
        }
        return b.append('}').toString();
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
