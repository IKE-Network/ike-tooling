package network.ike.tooling.buildreport;

import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.execution.MavenSession;

/**
 * Session-end gate enforcement (ike-issues#989).
 *
 * <p>At {@code afterSessionEnd} — after the receipt is on disk, thanks
 * to {@link ReportSession#finalizeAndWrite()}'s idempotence — this
 * participant fails the build when the ledger declares
 * {@code mode: gate} and gating ATTENTION exists. It never fails a
 * build that already failed, never enforces when the escape hatch
 * property ({@value ReportSession#GATE_SKIP_PROPERTY}) is set, and the
 * skip itself is recorded in the receipt. In {@code mode: report}
 * this participant is a spectator.</p>
 */
@Named("ike-build-report-gate")
@Singleton
public class GateKeeper extends AbstractMavenLifecycleParticipant {

    /** Creates the participant; instantiated by the sisu index, never directly. */
    public GateKeeper() {
    }

    /**
     * Enforces the gate verdict for the completed session.
     *
     * @param session the completed Maven session
     * @throws MavenExecutionException when the gate fails the build
     */
    @Override
    public void afterSessionEnd(MavenSession session) throws MavenExecutionException {
        GateVerdict verdict = ReportSession.finalizeAndWrite();
        if (!verdict.shouldFailBuild()) {
            return;
        }
        StringBuilder message = new StringBuilder(256);
        message.append("ike-build-report gate: ")
                .append(verdict.gatingAttention().size())
                .append(" gating attention item(s)");
        for (LedgerEvaluation.AttentionItem item : verdict.gatingAttention()) {
            message.append("\n  - ").append(item.key()).append(" — observed ").append(item.observed());
            if (item.expected() != null) {
                message.append(", accepted ").append(item.expected());
            } else {
                message.append(", not accepted");
            }
        }
        message.append("\nSee ")
                .append(verdict.receiptPath() != null
                        ? verdict.receiptPath()
                        : ReportSession.RECEIPT_FILE_NAME)
                .append("; accept via the ledger, or skip once with -D")
                .append(ReportSession.GATE_SKIP_PROPERTY).append("=true");
        throw new MavenExecutionException(message.toString(), (Throwable) null);
    }
}
