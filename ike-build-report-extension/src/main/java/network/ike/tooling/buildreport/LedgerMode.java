package network.ike.tooling.buildreport;

/**
 * Enforcement posture declared by a ledger.
 *
 * <p>Phase 1 parses both modes but enforces neither — the receipt is
 * always written, the build never fails on report content. Phase 2 arms
 * {@link #GATE}: session-end failure on unaccepted findings or count
 * regressions, after the receipt is written.</p>
 */
public enum LedgerMode {

    /** Report findings in the receipt; never fail the build. */
    REPORT,

    /** Phase 2: fail the build at session end on ATTENTION content. */
    GATE
}
