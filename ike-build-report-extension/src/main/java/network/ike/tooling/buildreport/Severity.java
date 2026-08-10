package network.ike.tooling.buildreport;

/**
 * How a {@link Finding} participates in ledger evaluation.
 *
 * <p>{@link #ERROR} findings always land in the receipt's FAILURES
 * section — the accepted ledger never absorbs a build-breaking event.
 * {@link #WARNING} findings are evaluated against the ledger's accepted
 * entries and their expected counts.</p>
 */
public enum Severity {

    /** Build-breaking; never accepted by the ledger. */
    ERROR,

    /** Noteworthy but not build-breaking; ledger-evaluated. */
    WARNING
}
