package pulse_api.connector;

/**
 * One class per upstream (spec §4). Connectors are dumb fetchers: pull, write snapshots/caches,
 * report a count. Every connector degrades to {@code configured()==false} when its env vars are
 * absent; the job runner then records "skipped" instead of calling it.
 */
public interface Connector {

    /** Short source id used in {@code metric_snapshot.source} and job results. */
    String source();

    boolean configured();

    FetchResult fetch(FetchWindow window);

    /** Whether the every-15-minutes {@code metrics} job runs this connector (uptime, billing and GitHub have their own jobs). */
    default boolean partOfMetricsJob() {
        return true;
    }
}
