package network.ike.tooling.buildreport;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

/**
 * Renders a {@link LedgerEvaluation} as the {@code ike꞉build-report.md}
 * receipt, in the house {@code ws꞉*.md} receipt style: a title line, a
 * tool/version/timestamp line, then only the sections that have content.
 */
public final class ReceiptRenderer {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private ReceiptRenderer() {
    }

    /**
     * Renders the receipt.
     *
     * @param toolVersion the extension version stamped under the title
     * @param timestamp   the session-end time stamped under the title
     * @param mode        the ledger's declared enforcement posture
     * @param ledgerNote  a one-line ledger status (for example a parse
     *                    failure); empty when the ledger loaded cleanly
     * @param evaluation  the section content
     * @return the receipt as Markdown
     */
    public static String render(
            String toolVersion,
            ZonedDateTime timestamp,
            LedgerMode mode,
            String ledgerNote,
            LedgerEvaluation evaluation) {
        Objects.requireNonNull(evaluation, "evaluation");
        StringBuilder out = new StringBuilder(1024);
        out.append("# ike:build-report\n");
        out.append('_').append(STAMP.format(timestamp))
                .append(" · ike-build-report-extension ").append(toolVersion)
                .append(" · mode: ").append(mode.name().toLowerCase(java.util.Locale.ROOT))
                .append("_\n\n");
        if (!ledgerNote.isBlank()) {
            out.append("> ").append(ledgerNote.strip()).append("\n\n");
        }

        renderFailures(out, evaluation.failures());
        renderAttention(out, evaluation.attention());
        renderAccepted(out, evaluation.accepted());
        renderRatchet(out, evaluation.ratchet());
        renderSummary(out, evaluation);
        return out.toString();
    }

    private static void renderFailures(StringBuilder out, List<Finding> failures) {
        if (failures.isEmpty()) {
            return;
        }
        out.append("## FAILURES\n\n");
        for (Finding finding : failures) {
            out.append("- `").append(finding.key()).append("` — ").append(finding.detail()).append('\n');
        }
        out.append('\n');
    }

    private static void renderAttention(StringBuilder out, List<LedgerEvaluation.AttentionItem> attention) {
        if (attention.isEmpty()) {
            return;
        }
        out.append("## ATTENTION\n\n");
        for (LedgerEvaluation.AttentionItem item : attention) {
            out.append("- `").append(item.key()).append("` — observed ").append(item.observed());
            if (item.expected() == null) {
                out.append(", not accepted");
            } else {
                out.append(", accepted ").append(item.expected());
            }
            if (!item.sample().isBlank()) {
                out.append(" — ").append(item.sample());
            }
            out.append('\n');
        }
        out.append('\n');
    }

    private static void renderAccepted(StringBuilder out, List<LedgerEvaluation.AcceptedStatus> accepted) {
        if (accepted.isEmpty()) {
            return;
        }
        out.append("## ACCEPTED\n\n");
        for (LedgerEvaluation.AcceptedStatus status : accepted) {
            out.append("- `").append(status.entry().key()).append("` — expected ")
                    .append(status.entry().count()).append(", observed ").append(status.observed());
            if (!status.entry().reason().isBlank()) {
                out.append(" — ").append(status.entry().reason());
            }
            out.append('\n');
        }
        out.append('\n');
    }

    private static void renderRatchet(StringBuilder out, List<LedgerEvaluation.AcceptedStatus> ratchet) {
        if (ratchet.isEmpty()) {
            return;
        }
        out.append("## RATCHET\n\n");
        for (LedgerEvaluation.AcceptedStatus status : ratchet) {
            out.append("- `").append(status.entry().key()).append("` — expected ")
                    .append(status.entry().count()).append(", observed ").append(status.observed())
                    .append(" — ledger can tighten\n");
        }
        out.append('\n');
    }

    private static void renderSummary(StringBuilder out, LedgerEvaluation evaluation) {
        out.append("## SUMMARY\n\n");
        out.append("failures: ").append(evaluation.failures().size())
                .append(" · attention: ").append(evaluation.attention().size())
                .append(" · accepted: ").append(evaluation.accepted().size())
                .append(" · ratchet: ").append(evaluation.ratchet().size())
                .append('\n');
    }
}
