package network.ike.tooling.buildreport;

import java.util.List;

/**
 * The result of comparing a session's findings against a {@link Ledger}.
 *
 * <p>The four lists map one-to-one onto receipt sections. A finding key
 * appears in exactly one of {@code attention}, {@code accepted}, or
 * {@code ratchet}; {@code failures} carries individual
 * {@link Severity#ERROR} findings, which the ledger never absorbs.</p>
 *
 * @param failures  build-breaking findings, in observation order
 * @param attention warning keys that are unaccepted or exceeded their count
 * @param accepted  ledger entries whose observations matched expectations
 * @param ratchet   ledger entries observed below their expected count
 */
public record LedgerEvaluation(
        List<Finding> failures,
        List<AttentionItem> attention,
        List<AcceptedStatus> accepted,
        List<AcceptedStatus> ratchet) {

    /**
     * One unaccepted or count-exceeding warning key.
     *
     * @param key      the finding key
     * @param observed how many times it was observed this session
     * @param expected the ledger's expected count, or {@code null} when
     *                 the key has no ledger entry at all
     * @param sample   one representative {@link Finding#detail()} line
     */
    public record AttentionItem(String key, long observed, Integer expected, String sample) {
    }

    /**
     * One ledger entry with its observation count for this session.
     *
     * @param entry    the accepted ledger entry
     * @param observed how many times its key was observed this session
     */
    public record AcceptedStatus(AcceptedEntry entry, long observed) {
    }

    /**
     * Reports whether anything demands a reader's action.
     *
     * @return {@code true} when there are failures or attention items
     */
    public boolean demandsAttention() {
        return !failures.isEmpty() || !attention.isEmpty();
    }
}
