/**
 * Build-report extension: distilled, ledger-compared build feedback.
 *
 * <p>{@link network.ike.tooling.buildreport.BuildReportSpy} collects
 * structured session events, {@link network.ike.tooling.buildreport.Ledger}
 * compares them against the committed acceptance ledger with expected
 * counts, and {@link network.ike.tooling.buildreport.ReceiptRenderer}
 * writes the {@code ike꞉build-report.md} receipt at session end. See
 * the dev-build-report-extension design note (ike-lab-documents) and
 * IKE-Network/ike-issues#978.</p>
 */
package network.ike.tooling.buildreport;
