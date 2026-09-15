package pulse_api.connector;

import java.util.ArrayList;
import java.util.List;

/** Rows written plus any non-fatal problems. Never throws across connectors. */
public record FetchResult(int snapshotsWritten, List<String> errors) {

    public static FetchResult of(int written) {
        return new FetchResult(written, List.of());
    }

    public static FetchResult of(int written, List<String> errors) {
        return new FetchResult(written, List.copyOf(errors));
    }

    public static FetchResult skipped(String source) {
        return new FetchResult(0, List.of(source + ": skipped (not configured)"));
    }

    public static FetchResult failed(String source, Throwable t) {
        return new FetchResult(0, List.of(source + ": " + describe(t)));
    }

    public FetchResult plus(FetchResult other) {
        List<String> all = new ArrayList<>(errors);
        all.addAll(other.errors);
        return new FetchResult(snapshotsWritten + other.snapshotsWritten, all);
    }

    static String describe(Throwable t) {
        String msg = t.getMessage();
        String text = (msg == null || msg.isBlank()) ? t.getClass().getSimpleName() : msg;
        return text.length() > 300 ? text.substring(0, 300) : text;
    }
}
