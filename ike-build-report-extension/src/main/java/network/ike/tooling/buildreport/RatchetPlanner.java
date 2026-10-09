package network.ike.tooling.buildreport;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Computes the tightening of a ledger against the latest session's
 * observations: accepted counts move down, measure bounds move the
 * one way each is allowed.
 *
 * <p>The asymmetry is deliberate: an accepted count only moves
 * <em>down</em> mechanically (an accepted finding class shrank —
 * tighten the baseline, keeping the entry at 0 as a documented tripwire
 * rather than deleting the acceptance record), and a measure bound only
 * tightens ({@code at-most} down, {@code at-least} up, each keeping its
 * slack). Accepting new findings, grown counts or a regressed measure
 * is always a human edit.</p>
 */
public final class RatchetPlanner {

    /**
     * One proposed tightening of an accepted count.
     *
     * @param entry    the ledger entry as it stands
     * @param observed the latest session's observation for its key
     */
    public record Tightening(AcceptedEntry entry, long observed) {
    }

    /**
     * One proposed tightening of a measure bound.
     *
     * @param entry    the bound as it stands
     * @param observed the latest session's value for its key
     * @param limit    the limit the bound would move to
     */
    public record MeasureTightening(MeasureEntry entry, double observed, double limit) {
    }

    /**
     * A computed ratchet plan.
     *
     * @param tightenings        accepted entries whose counts would move down
     * @param measureTightenings measure bounds that would tighten
     * @param result             the ledger with every tightening applied
     */
    public record Plan(List<Tightening> tightenings, List<MeasureTightening> measureTightenings, Ledger result) {

        /**
         * Defensively copies the lists.
         *
         * @param tightenings        accepted entries whose counts would move down
         * @param measureTightenings measure bounds that would tighten
         * @param result             the ledger with every tightening applied
         */
        public Plan {
            tightenings = List.copyOf(tightenings);
            measureTightenings = List.copyOf(measureTightenings);
            Objects.requireNonNull(result, "result");
        }

        /**
         * Reports whether the plan changes anything.
         *
         * @return {@code true} when at least one count or bound tightens
         */
        public boolean changesAnything() {
            return !tightenings.isEmpty() || !measureTightenings.isEmpty();
        }
    }

    private RatchetPlanner() {
    }

    /**
     * Plans the tightening of a ledger's accepted counts.
     *
     * @param ledger   the current ledger
     * @param observed per-key observed counts from the latest session's
     *                 sidecar; keys absent from the map observed nothing
     * @return the plan; entries never loosen, and entries at or below
     *         their observation are untouched
     */
    public static Plan plan(Ledger ledger, Map<String, Long> observed) {
        return plan(ledger, observed, Map.of());
    }

    /**
     * Plans the tightening of a ledger's accepted counts and measure
     * bounds.
     *
     * @param ledger   the current ledger
     * @param observed per-key observed counts from the latest session's
     *                 sidecar; keys absent from the map observed nothing
     * @param measures the latest session's measures; a bound whose key
     *                 is absent was not measured and is left alone
     * @return the plan; nothing loosens
     */
    public static Plan plan(Ledger ledger, Map<String, Long> observed, Map<String, Double> measures) {
        Objects.requireNonNull(ledger, "ledger");
        Objects.requireNonNull(observed, "observed");
        Objects.requireNonNull(measures, "measures");
        List<Tightening> tightenings = new ArrayList<>();
        List<AcceptedEntry> resultEntries = new ArrayList<>();
        for (AcceptedEntry entry : ledger.entries()) {
            long seen = observed.getOrDefault(entry.key(), 0L);
            if (seen < entry.count()) {
                tightenings.add(new Tightening(entry, seen));
                resultEntries.add(new AcceptedEntry(
                        entry.key(), (int) seen, entry.reason(), entry.since(), entry.entryMode()));
            } else {
                resultEntries.add(entry);
            }
        }
        List<MeasureTightening> measureTightenings = new ArrayList<>();
        List<MeasureEntry> resultMeasures = new ArrayList<>();
        for (MeasureEntry entry : ledger.measures()) {
            Double value = measures.get(entry.key());
            if (value != null && !entry.violatedBy(value) && entry.canTightenTo(value)) {
                double limit = entry.tightened(value);
                measureTightenings.add(new MeasureTightening(entry, value, limit));
                resultMeasures.add(entry.withLimit(limit));
            } else {
                resultMeasures.add(entry);
            }
        }
        return new Plan(tightenings, measureTightenings, Ledger.of(
                ledger.mode(), resultEntries, ledger.consoleIgnores(), resultMeasures, ledger.sizes()));
    }
}
