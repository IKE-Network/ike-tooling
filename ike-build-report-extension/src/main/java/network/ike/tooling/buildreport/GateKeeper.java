package network.ike.tooling.buildreport;

import java.util.List;
import java.util.Map;

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

    /** How many occurrences of one key the console message shows. */
    private static final int MAX_CONSOLE_OCCURRENCES = 3;

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
        StringBuilder message = new StringBuilder(512);
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
            appendEvidence(message, item);
        }
        message.append("\nSee ")
                .append(verdict.receiptPath() != null
                        ? verdict.receiptPath()
                        : ReportSession.RECEIPT_FILE_NAME)
                .append(" for the full evidence and the options");
        if (verdict.archivePath() != null) {
            message.append("\nArchived, so a later clean run cannot overwrite it: ")
                    .append(verdict.archivePath());
        }
        message.append("\nAccept via ").append(ReportSession.LEDGER_RELATIVE_PATH)
                .append(", or skip once with -D")
                .append(ReportSession.GATE_SKIP_PROPERTY).append("=true");
        throw new MavenExecutionException(message.toString(), (Throwable) null);
    }

    /**
     * Appends what the item actually was, under its summary line.
     *
     * <p>The console message is where most readers stop, so it carries
     * the same identifying evidence as the receipt: the repository and
     * its URL, who declared it, and the first occurrences verbatim. A
     * key and a count alone left the reader with nothing to act on
     * (ike-issues#989).</p>
     *
     * @param message the message under construction
     * @param item    the gating item to describe
     */
    private static void appendEvidence(StringBuilder message, LedgerEvaluation.AttentionItem item) {
        if (item.findings().isEmpty()) {
            return;
        }
        Map<String, String> context = item.findings().get(0).context();
        String repositoryId = context.get(Finding.CONTEXT_REPOSITORY_ID);
        String url = context.get(Finding.CONTEXT_REPOSITORY_URL);
        if (repositoryId != null) {
            message.append("\n      repository ").append(repositoryId);
            if (url != null && !url.isBlank()) {
                message.append(" at ").append(url);
            }
        }
        String declaredBy = context.get(Finding.CONTEXT_DECLARED_BY);
        if (declaredBy != null && !declaredBy.isBlank()) {
            message.append("\n      ").append(declaredBy);
        }
        List<Finding> findings = item.findings();
        int shown = Math.min(findings.size(), MAX_CONSOLE_OCCURRENCES);
        for (int index = 0; index < shown; index++) {
            String detail = findings.get(index).detail();
            if (!detail.isBlank()) {
                message.append("\n      ").append(detail);
            }
        }
        if (findings.size() > shown) {
            message.append("\n      … and ").append(findings.size() - shown)
                    .append(" further occurrence(s) in the receipt");
        }
    }
}
