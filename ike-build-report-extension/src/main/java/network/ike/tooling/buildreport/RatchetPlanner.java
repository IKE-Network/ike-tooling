package network.ike.tooling.buildreport;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Computes the downward-only tightening of a ledger against the latest
 * session's observed counts.
 *
 * <p>The asymmetry is deliberate: counts only move <em>down</em>
 * mechanically (an accepted finding class shrank — tighten the
 * baseline, keeping the entry at 0 as a documented tripwire rather
 * than deleting the acceptance record). Accepting new findings or
 * grown counts is always a human edit.</p>
 */
public final class RatchetPlanner {

    /**
     * One proposed tightening.
     *
     * @param entry    the ledger entry as it stands
     * @param observed the latest session's observation for its key
     */
    public record Tightening(AcceptedEntry entry, long observed) {
    }

    /**
     * A computed ratchet plan.
     *
     * @param tightenings entries whose counts would move down
     * @param result      the ledger with tightenings applied
     */
    public record Plan(List<Tightening> tightenings, Ledger result) {

        /**
         * Reports whether the plan changes anything.
         *
         * @return {@code true} when at least one count tightens
         */
        public boolean changesAnything() {
            return !tightenings.isEmpty();
        }
    }

    private RatchetPlanner() {
    }

    /**
     * Plans the tightening of a ledger against observed counts.
     *
     * @param ledger   the current ledger
     * @param observed per-key observed counts from the latest session's
     *                 sidecar; keys absent from the map observed nothing
     * @return the plan; entries never loosen, and entries at or below
     *         their observation are untouched
     */
    public static Plan plan(Ledger ledger, Map<String, Long> observed) {
        Objects.requireNonNull(ledger, "ledger");
        Objects.requireNonNull(observed, "observed");
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
        return new Plan(tightenings, Ledger.of(ledger.mode(), resultEntries));
    }
}
