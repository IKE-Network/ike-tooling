package network.ike.tooling.buildreport;

import java.util.Objects;

/**
 * One accepted finding in the committed ledger, with its expected count.
 *
 * <p>Count semantics keep acceptance visible without being noisy:
 * observed equal to {@code count} lists under ACCEPTED; observed above
 * it surfaces the delta under ATTENTION; observed below it surfaces a
 * ratchet opportunity.</p>
 *
 * @param key       the stable {@link Finding#key()} this entry accepts
 * @param count     expected number of observations per session
 * @param reason    why the finding is accepted — carried into the receipt
 * @param since     ISO date the acceptance was recorded
 * @param entryMode per-entry enforcement override: {@link LedgerMode#REPORT}
 *                  exempts this entry's over-counts from gating while the
 *                  global mode is {@code gate} (onboarding); {@code null}
 *                  inherits the global mode
 */
public record AcceptedEntry(String key, int count, String reason, String since, LedgerMode entryMode) {

    /**
     * Validates the entry's identity and count.
     *
     * @param key       the stable finding key this entry accepts
     * @param count     expected number of observations per session; must not be negative
     * @param reason    why the finding is accepted
     * @param since     ISO date the acceptance was recorded
     * @param entryMode per-entry enforcement override; {@code null} inherits
     */
    public AcceptedEntry {
        Objects.requireNonNull(key, "key");
        if (count < 0) {
            throw new IllegalArgumentException("count must not be negative: " + count);
        }
        reason = reason == null ? "" : reason.strip();
        since = since == null ? "" : since;
    }

    /**
     * Creates an entry with no per-entry mode override.
     *
     * @param key    the stable finding key this entry accepts
     * @param count  expected number of observations per session
     * @param reason why the finding is accepted
     * @param since  ISO date the acceptance was recorded
     */
    public AcceptedEntry(String key, int count, String reason, String since) {
        this(key, count, reason, since, null);
    }
}
