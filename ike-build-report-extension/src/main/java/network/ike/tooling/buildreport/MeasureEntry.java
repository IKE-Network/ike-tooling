package network.ike.tooling.buildreport;

import java.util.Locale;
import java.util.Objects;

/**
 * One bound the ledger places on a measure (ike-issues#1207): a count
 * that may not rise above a value, or a coverage that may not fall
 * below one.
 *
 * <p>A bound is ratcheted the way accepted counts are, in the one
 * direction that tightens it: {@code at-most} only moves down,
 * {@code at-least} only moves up, and loosening is a human edit.
 * {@code slack} is the room a mechanical tightening leaves — a
 * coverage bound set half a point under the observation does not trip
 * on the next build's rounding.</p>
 *
 * @param key       the measure the bound applies to
 * @param bound     which way the measure may not move
 * @param limit     the value it may not pass
 * @param slack     the margin a tightening keeps; zero for exact counts
 * @param reason    why the bound is where it is — carried into the receipt
 * @param since     ISO date the bound was recorded
 * @param entryMode per-entry enforcement override, as for
 *                  {@link AcceptedEntry#entryMode()}; {@code null} inherits
 */
public record MeasureEntry(
        String key,
        Bound bound,
        double limit,
        double slack,
        String reason,
        String since,
        LedgerMode entryMode) {

    /** Which way a bounded measure may not move. */
    public enum Bound {

        /** The measure may not rise above the limit: warnings, skips, time. */
        AT_MOST("at-most"),

        /** The measure may not fall below the limit: coverage. */
        AT_LEAST("at-least");

        private final String yaml;

        Bound(String yaml) {
            this.yaml = yaml;
        }

        /**
         * Returns the bound's field name in the ledger.
         *
         * @return {@code at-most} or {@code at-least}
         */
        public String yaml() {
            return yaml;
        }
    }

    /**
     * Validates the entry.
     *
     * @param key       the measure the bound applies to
     * @param bound     which way the measure may not move
     * @param limit     the value it may not pass; must be a finite number
     * @param slack     the margin a tightening keeps; must not be negative
     * @param reason    why the bound is where it is; null becomes empty
     * @param since     ISO date the bound was recorded; null becomes empty
     * @param entryMode per-entry enforcement override; {@code null} inherits
     */
    public MeasureEntry {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(bound, "bound");
        if (key.isBlank()) {
            throw new IllegalArgumentException("a measure entry needs a non-blank key");
        }
        if (Double.isNaN(limit) || Double.isInfinite(limit)) {
            throw new IllegalArgumentException("measure entry '" + key + "' needs a finite limit, was: " + limit);
        }
        if (Double.isNaN(slack) || slack < 0) {
            throw new IllegalArgumentException("measure entry '" + key + "' slack must not be negative: " + slack);
        }
        reason = reason == null ? "" : reason.strip();
        since = since == null ? "" : since;
    }

    /**
     * Creates an exact bound with no reason, date or mode override.
     *
     * @param key   the measure the bound applies to
     * @param bound which way the measure may not move
     * @param limit the value it may not pass
     */
    public MeasureEntry(String key, Bound bound, double limit) {
        this(key, bound, limit, 0.0, "", "", null);
    }

    /**
     * Says whether an observation passes the bound.
     *
     * @param observed the session's value
     * @return {@code true} when the value is above an {@code at-most}
     *         limit or below an {@code at-least} limit
     */
    public boolean violatedBy(double observed) {
        return switch (bound) {
            case AT_MOST -> observed > limit;
            case AT_LEAST -> observed < limit;
        };
    }

    /**
     * Computes the limit a mechanical tightening would move to.
     *
     * @param observed the session's value
     * @return the observation plus or minus the slack, or the current
     *         limit when that would loosen it
     */
    public double tightened(double observed) {
        return switch (bound) {
            case AT_MOST -> Math.min(limit, observed + slack);
            case AT_LEAST -> Math.max(limit, observed - slack);
        };
    }

    /**
     * Says whether an observation lets the bound tighten.
     *
     * @param observed the session's value
     * @return {@code true} when {@link #tightened(double)} differs from
     *         the current limit
     */
    public boolean canTightenTo(double observed) {
        return tightened(observed) != limit;
    }

    /**
     * Returns a copy with another limit.
     *
     * @param newLimit the limit for the copy
     * @return the copy; every other field unchanged
     */
    public MeasureEntry withLimit(double newLimit) {
        return new MeasureEntry(key, bound, newLimit, slack, reason, since, entryMode);
    }

    /**
     * Describes the bound the way the ledger writes it.
     *
     * @return for example {@code at-most 415}
     */
    public String describe() {
        return bound.yaml() + " " + Numbers.plain(limit);
    }

    /**
     * Parses a bound's field name.
     *
     * @param text {@code at-most} or {@code at-least}, in any case
     * @return the bound, or {@code null} when the text names neither
     */
    public static Bound boundOf(String text) {
        if (text == null) {
            return null;
        }
        String wanted = text.strip().toLowerCase(Locale.ROOT);
        for (Bound candidate : Bound.values()) {
            if (candidate.yaml.equals(wanted)) {
                return candidate;
            }
        }
        return null;
    }
}
