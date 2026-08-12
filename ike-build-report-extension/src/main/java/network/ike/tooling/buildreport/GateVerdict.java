package network.ike.tooling.buildreport;

import java.nio.file.Path;
import java.util.List;

/**
 * The session's enforcement outcome, computed exactly once when the
 * receipt is written.
 *
 * <p>The verdict is evaluated by {@link ReportSession#finalizeAndWrite()}
 * and consumed by the gate participant at {@code afterSessionEnd}. The
 * receipt is always complete before the verdict is acted on.</p>
 *
 * @param mode               the ledger's declared enforcement posture
 * @param gatingAttention    attention items that gate (unaccepted keys,
 *                           or exceeded counts whose entry does not carry
 *                           a report-mode override)
 * @param buildAlreadyFailed whether ERROR findings were observed — a
 *                           build that already failed is never
 *                           double-failed by the gate
 * @param skipRequested      whether the escape hatch property was set
 *                           for this invocation
 * @param receiptPath        where the receipt was written, for the
 *                           failure message; {@code null} when writing
 *                           failed
 */
public record GateVerdict(
        LedgerMode mode,
        List<LedgerEvaluation.AttentionItem> gatingAttention,
        boolean buildAlreadyFailed,
        boolean skipRequested,
        Path receiptPath) {

    /**
     * Decides whether the gate should fail the build.
     *
     * @return {@code true} when gate mode is armed, the escape hatch is
     *         not set, the build has not already failed, and gating
     *         attention exists
     */
    public boolean shouldFailBuild() {
        return mode == LedgerMode.GATE
                && !skipRequested
                && !buildAlreadyFailed
                && !gatingAttention.isEmpty();
    }
}
