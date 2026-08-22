package network.ike.tooling.buildreport;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Renders a {@link LedgerEvaluation} as the {@code ike꞉build-report.md}
 * receipt, in the house {@code ws꞉*.md} receipt style: a title line, a
 * tool/version/timestamp line, then only the sections that have content.
 */
public final class ReceiptRenderer {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** How many occurrences of one key the receipt lists before summarizing. */
    private static final int MAX_OCCURRENCES = 5;

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
            for (String line : ledgerNote.strip().split("\n")) {
                out.append("> ").append(line.strip()).append('\n');
            }
            out.append('\n');
        }

        renderFailures(out, evaluation.failures());
        renderAttention(out, evaluation.attention());
        renderAccepted(out, evaluation.accepted());
        renderRatchet(out, evaluation.ratchet());
        renderSummary(out, evaluation);
        renderRemediation(out, evaluation);
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
            out.append('\n');
            renderEvidence(out, item);
            renderOccurrences(out, item.findings());
        }
        out.append('\n');
    }

    /**
     * Renders the structured context shared by an item's findings —
     * what the thing is, and whose it is.
     *
     * <p>Only identifying context is promoted here; the per-occurrence
     * subject and error stay with their occurrence lines, where the
     * count makes them meaningful.</p>
     */
    private static void renderEvidence(StringBuilder out, LedgerEvaluation.AttentionItem item) {
        if (item.findings().isEmpty()) {
            return;
        }
        Map<String, String> context = item.findings().get(0).context();
        String repositoryId = context.get(Finding.CONTEXT_REPOSITORY_ID);
        String url = context.get(Finding.CONTEXT_REPOSITORY_URL);
        if (repositoryId != null) {
            out.append("  - repository `").append(repositoryId).append('`');
            if (url != null && !url.isBlank()) {
                out.append(" at `").append(url).append('`');
            }
            out.append('\n');
        }
        String declaredBy = context.get(Finding.CONTEXT_DECLARED_BY);
        if (declaredBy != null && !declaredBy.isBlank()) {
            out.append("  - ").append(declaredBy).append('\n');
        }
    }

    private static void renderOccurrences(StringBuilder out, List<Finding> findings) {
        int shown = Math.min(findings.size(), MAX_OCCURRENCES);
        for (int index = 0; index < shown; index++) {
            String detail = findings.get(index).detail();
            if (detail.isBlank()) {
                continue;
            }
            out.append("  - occurrence ").append(index + 1).append('/').append(findings.size())
                    .append(" — ").append(detail).append('\n');
        }
        if (findings.size() > shown) {
            out.append("  - … and ").append(findings.size() - shown)
                    .append(" further occurrence(s)\n");
        }
    }

    /**
     * Renders the options a reader has for each family of finding
     * present, so the receipt closes the loop it opens.
     *
     * <p>A finding that gates a build is only actionable if the reader
     * knows the responses available; naming them here is what turns the
     * receipt from a verdict into an instruction (ike-issues#989).</p>
     */
    private static void renderRemediation(StringBuilder out, LedgerEvaluation evaluation) {
        Set<FindingCategory> categories = new LinkedHashSet<>();
        for (LedgerEvaluation.AttentionItem item : evaluation.attention()) {
            if (item.category() != null) {
                categories.add(item.category());
            }
        }
        for (Finding failure : evaluation.failures()) {
            categories.add(failure.category());
        }
        if (categories.isEmpty()) {
            return;
        }
        out.append("\n## WHAT TO DO\n\n");
        if (categories.contains(FindingCategory.REPOSITORY)) {
            out.append("Repository findings are transfer-level failures, not missing artifacts —\n");
            out.append("a repository the build could not talk to at all. Options, in the order\n");
            out.append("worth considering:\n\n");
            out.append("1. **Not yours** — when the repository was declared by a dependency's\n");
            out.append("   POM (the `declared by the POM of …` line above), no change in this\n");
            out.append("   workspace will remove it. Block it once, for every build, in\n");
            out.append("   `~/.m2/settings.xml`:\n\n");
            out.append("   ```xml\n");
            out.append("   <mirror>\n");
            out.append("     <id>block-EXAMPLE</id>\n");
            out.append("     <mirrorOf>EXAMPLE</mirrorOf>\n");
            out.append("     <name>unreachable; declared by a third-party POM</name>\n");
            out.append("     <url>http://0.0.0.0/</url>\n");
            out.append("     <blocked>true</blocked>\n");
            out.append("   </mirror>\n");
            out.append("   ```\n\n");
            out.append("2. **Yours** — fix the URL, the credentials, or the network path.\n");
            out.append("3. **Known and tolerated** — accept the key in `")
                    .append(ReportSession.LEDGER_RELATIVE_PATH)
                    .append("` with a\n   `count` and a `reason` that says why it is tolerable.\n\n");
        }
        if (categories.contains(FindingCategory.MODEL)) {
            out.append("Model findings are recomputed from the POMs themselves, not scraped\n");
            out.append("from Maven's log. Either change the declaration the finding names, or\n");
            out.append("accept the key in `").append(ReportSession.LEDGER_RELATIVE_PATH)
                    .append("` with a reason.\n\n");
        }
        if (categories.contains(FindingCategory.EXECUTION)) {
            out.append("Execution findings are build-breaking and are never absorbed by the\n");
            out.append("ledger — fix the failing goal.\n\n");
        }
        out.append("To let one invocation through without changing anything, add\n");
        out.append("`-D").append(ReportSession.GATE_SKIP_PROPERTY).append("=true`;\n");
        out.append("the skip is recorded in this receipt.\n\n");
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
