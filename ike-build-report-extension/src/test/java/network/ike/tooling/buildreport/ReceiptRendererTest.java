package network.ike.tooling.buildreport;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Receipt rendering, driven by real {@link Ledger#evaluate(List)}
 * output rather than hand-assembled evaluations.
 */
class ReceiptRendererTest {

    private static final ZonedDateTime STAMP =
            ZonedDateTime.of(2026, 8, 10, 16, 30, 0, 0, ZoneId.of("America/Los_Angeles"));

    @Test
    void cleanSessionRendersSummaryOnly() {
        LedgerEvaluation evaluation = Ledger.empty().evaluate(List.of());

        String receipt = ReceiptRenderer.render("244-SNAPSHOT", STAMP, LedgerMode.REPORT, "", evaluation);

        assertThat(receipt).startsWith("# ike:build-report\n");
        assertThat(receipt).contains("_2026-08-10 16:30:00 · ike-build-report-extension 244-SNAPSHOT · mode: report_");
        assertThat(receipt).contains("## SUMMARY");
        assertThat(receipt).contains("failures: 0 · attention: 0 · accepted: 0 · ratchet: 0");
        assertThat(receipt).doesNotContain("## FAILURES");
        assertThat(receipt).doesNotContain("## ATTENTION");
        assertThat(receipt).doesNotContain("## ACCEPTED");
        assertThat(receipt).doesNotContain("## RATCHET");
    }

    @Test
    void failuresAndAttentionRenderWithDetailAndCounts() {
        LedgerEvaluation evaluation = Ledger.empty().evaluate(List.of(
                new Finding(FindingCategory.EXECUTION, Severity.ERROR,
                        "execution/mojo-failed/maven-compiler-plugin:compile", "komet-desktop: compilation failed"),
                new Finding(FindingCategory.REPOSITORY, Severity.WARNING,
                        "repository/metadata-resolve-failed/private-assets",
                        "dev.ikm.elk metadata: authentication rejected")));

        String receipt = ReceiptRenderer.render("244-SNAPSHOT", STAMP, LedgerMode.REPORT, "", evaluation);

        assertThat(receipt).contains("## FAILURES");
        assertThat(receipt).contains(
                "- `execution/mojo-failed/maven-compiler-plugin:compile` — komet-desktop: compilation failed");
        assertThat(receipt).contains("## ATTENTION");
        assertThat(receipt).contains(
                "- `repository/metadata-resolve-failed/private-assets` — observed 1, not accepted"
                        + " — dev.ikm.elk metadata: authentication rejected");
        assertThat(receipt).contains("failures: 1 · attention: 1");
    }

    @Test
    void acceptedAndRatchetRenderExpectedVersusObserved() {
        Ledger ledger = ledger("""
                accepted:
                  - key: model/bom-import
                    count: 2
                    reason: accepted by design
                    since: 2026-08-10
                  - key: model/retired-warning
                    count: 3
                    reason: warning train baseline
                    since: 2026-08-10
                """);
        LedgerEvaluation evaluation = ledger.evaluate(List.of(
                new Finding(FindingCategory.MODEL, Severity.WARNING, "model/bom-import", "rocks-kb"),
                new Finding(FindingCategory.MODEL, Severity.WARNING, "model/bom-import", "komet-claude-plugin")));

        String receipt = ReceiptRenderer.render("244-SNAPSHOT", STAMP, LedgerMode.REPORT, "", evaluation);

        assertThat(receipt).contains("## ACCEPTED");
        assertThat(receipt).contains("- `model/bom-import` — expected 2, observed 2 — accepted by design");
        assertThat(receipt).contains("## RATCHET");
        assertThat(receipt).contains("- `model/retired-warning` — expected 3, observed 0 — ledger can tighten");
    }

    @Test
    void ledgerNoteRendersAsBlockquote() {
        LedgerEvaluation evaluation = Ledger.empty().evaluate(List.of());

        String receipt = ReceiptRenderer.render(
                "244-SNAPSHOT", STAMP, LedgerMode.REPORT,
                "ledger .mvn/build-report.yaml not used: mode must be report or gate, was: enforce",
                evaluation);

        assertThat(receipt).contains("> ledger .mvn/build-report.yaml not used: mode must be report or gate");
    }

    private static Ledger ledger(String yaml) {
        try {
            java.nio.file.Path file = java.nio.file.Files.createTempFile("build-report", ".yaml");
            file.toFile().deleteOnExit();
            java.nio.file.Files.writeString(file, yaml);
            return Ledger.load(file);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
