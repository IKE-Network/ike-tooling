package network.ike.tooling.buildreport;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * How one ledger bound fared against the session's measures.
 *
 * <p>A violated bound raises a finding under {@value #FINDING_PREFIX}
 * plus the measure key, so it travels the same road as every other
 * finding: ATTENTION in the receipt, the gate in {@code mode: gate},
 * and the per-entry {@code mode: report} exemption. A bound whose
 * measure was not taken this session is neither passed nor violated;
 * the receipt says so.</p>
 *
 * @param entry    the bound as the ledger declares it
 * @param observed the session's value, or {@code null} when the measure
 *                 was not taken
 */
public record MeasureStatus(MeasureEntry entry, Double observed) {

    /** The key prefix of the findings violated bounds raise. */
    public static final String FINDING_PREFIX = "measure/";

    /** Context key: the session's value. */
    public static final String CONTEXT_OBSERVED = "observed";

    /** Context key: the bound as the ledger declares it. */
    public static final String CONTEXT_BOUND = "bound";

    /** Context key: the ledger's reason for the bound. */
    public static final String CONTEXT_REASON = "reason";

    /**
     * Validates the status.
     *
     * @param entry    the bound as the ledger declares it
     * @param observed the session's value, or {@code null}
     */
    public MeasureStatus {
        Objects.requireNonNull(entry, "entry");
    }

    /**
     * Says whether the measure was taken this session.
     *
     * @return {@code true} when there is a value to compare
     */
    public boolean measured() {
        return observed != null;
    }

    /**
     * Says whether the session's value passes the bound.
     *
     * @return {@code true} when measured and outside the bound
     */
    public boolean violated() {
        return measured() && entry.violatedBy(observed);
    }

    /**
     * Says whether the session's value lets the bound tighten.
     *
     * @return {@code true} when measured, within the bound, and better
     *         than the limit by more than the slack
     */
    public boolean canTighten() {
        return measured() && !violated() && entry.canTightenTo(observed);
    }

    /**
     * Computes the limit a tightening would move to.
     *
     * @return the tightened limit
     * @throws IllegalStateException when the measure was not taken
     */
    public double tightened() {
        if (!measured()) {
            throw new IllegalStateException(entry.key() + " was not measured");
        }
        return entry.tightened(observed);
    }

    /**
     * Returns the finding a violated bound raises.
     *
     * @return the finding, or {@code null} when the bound is not violated
     */
    public Finding finding() {
        if (!violated()) {
            return null;
        }
        String direction = entry.bound() == MeasureEntry.Bound.AT_MOST ? "above" : "below";
        String detail = entry.key() + " is " + Numbers.plain(observed) + ", " + direction
                + " the ledger's " + entry.describe();
        Map<String, String> context = new LinkedHashMap<>();
        context.put(CONTEXT_OBSERVED, Numbers.plain(observed));
        context.put(CONTEXT_BOUND, entry.describe());
        if (!entry.reason().isBlank()) {
            context.put(CONTEXT_REASON, entry.reason());
        }
        return new Finding(FindingCategory.MEASURE, Severity.WARNING,
                FINDING_PREFIX + entry.key(), detail, context);
    }

    /**
     * Recovers the measure key from a finding key a violation raised.
     *
     * @param findingKey the finding's key
     * @return the measure key, or {@code null} when the finding is not
     *         a measure violation
     */
    public static String measureKey(String findingKey) {
        if (findingKey == null || !findingKey.startsWith(FINDING_PREFIX)) {
            return null;
        }
        return findingKey.substring(FINDING_PREFIX.length());
    }
}
