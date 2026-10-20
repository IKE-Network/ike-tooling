package network.ike.tooling.buildreport;

import java.util.List;
import java.util.Objects;

/**
 * The session's measures with the ledger's verdict on each bound, as
 * the receipt presents them.
 *
 * @param measures  what the session measured
 * @param statuses  every ledger bound with how it fared, in ledger order
 * @param published how many statistics were sent to TeamCity; zero
 *                  outside a TeamCity build
 */
public record MeasureReport(Measures measures, List<MeasureStatus> statuses, int published) {

    /**
     * Validates the report.
     *
     * @param measures  what the session measured
     * @param statuses  every ledger bound with how it fared
     * @param published how many statistics were sent to TeamCity
     */
    public MeasureReport {
        Objects.requireNonNull(measures, "measures");
        statuses = List.copyOf(Objects.requireNonNullElse(statuses, List.of()));
        if (published < 0) {
            throw new IllegalArgumentException("published must not be negative: " + published);
        }
    }

    /**
     * Creates a report nothing has been published from.
     *
     * @param measures what the session measured
     * @param statuses every ledger bound with how it fared
     * @return the report
     */
    public static MeasureReport of(Measures measures, List<MeasureStatus> statuses) {
        return new MeasureReport(measures, statuses, 0);
    }

    /**
     * Returns a copy that records a publication.
     *
     * @param count how many statistics were published
     * @return the copy
     */
    public MeasureReport withPublished(int count) {
        return new MeasureReport(measures, statuses, count);
    }

    /**
     * Says whether there is anything to render.
     *
     * @return {@code true} when a measure, a bound or a note exists
     */
    public boolean hasContent() {
        return !measures.isEmpty() || !statuses.isEmpty() || !measures.notes().isEmpty();
    }
}
