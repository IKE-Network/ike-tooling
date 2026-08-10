package network.ike.tooling.buildreport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ledger loading and count-semantics evaluation, against real files and
 * real findings — no test doubles.
 */
class LedgerTest {

    @TempDir
    Path tempDir;

    private static Finding warning(String key, String detail) {
        return new Finding(FindingCategory.MODEL, Severity.WARNING, key, detail);
    }

    private static Finding error(String key, String detail) {
        return new Finding(FindingCategory.EXECUTION, Severity.ERROR, key, detail);
    }

    @Test
    void missingFileYieldsEmptyReportModeLedger() throws IOException {
        Ledger ledger = Ledger.load(tempDir.resolve("absent.yaml"));

        assertThat(ledger.mode()).isEqualTo(LedgerMode.REPORT);
        assertThat(ledger.entries()).isEmpty();
    }

    @Test
    void loadsModeAndEntriesFromYaml() throws IOException {
        Path file = tempDir.resolve("build-report.yaml");
        Files.writeString(file, """
                mode: gate
                accepted:
                  - key: model-problem/bom-import-from-reactor
                    count: 2
                    reason: accepted by design
                    since: 2026-08-10
                """);

        Ledger ledger = Ledger.load(file);

        assertThat(ledger.mode()).isEqualTo(LedgerMode.GATE);
        assertThat(ledger.entries()).containsExactly(
                new AcceptedEntry("model-problem/bom-import-from-reactor", 2, "accepted by design", "2026-08-10"));
    }

    @Test
    void rejectsEntryWithoutIntegerCount() throws IOException {
        Path file = tempDir.resolve("build-report.yaml");
        Files.writeString(file, """
                accepted:
                  - key: some/key
                    count: many
                """);

        assertThatThrownBy(() -> Ledger.load(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("some/key")
                .hasMessageContaining("integer count");
    }

    @Test
    void rejectsUnknownMode() throws IOException {
        Path file = tempDir.resolve("build-report.yaml");
        Files.writeString(file, "mode: enforce\n");

        assertThatThrownBy(() -> Ledger.load(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("report or gate");
    }

    @Test
    void errorFindingsAlwaysLandInFailures() throws IOException {
        Path file = tempDir.resolve("build-report.yaml");
        Files.writeString(file, """
                accepted:
                  - key: execution/mojo-failed/some-plugin:goal
                    count: 1
                """);
        Ledger ledger = Ledger.load(file);

        LedgerEvaluation evaluation = ledger.evaluate(
                List.of(error("execution/mojo-failed/some-plugin:goal", "compilation failed")));

        assertThat(evaluation.failures()).hasSize(1);
        assertThat(evaluation.accepted()).isEmpty();
        assertThat(evaluation.demandsAttention()).isTrue();
    }

    @Test
    void observedEqualToExpectedIsAcceptedOnly() {
        Ledger ledger = ledgerAccepting("model/bom-import", 2);

        LedgerEvaluation evaluation = ledger.evaluate(List.of(
                warning("model/bom-import", "rocks-kb"),
                warning("model/bom-import", "komet-claude-plugin")));

        assertThat(evaluation.accepted()).hasSize(1);
        assertThat(evaluation.accepted().get(0).observed()).isEqualTo(2);
        assertThat(evaluation.attention()).isEmpty();
        assertThat(evaluation.ratchet()).isEmpty();
        assertThat(evaluation.demandsAttention()).isFalse();
    }

    @Test
    void observedAboveExpectedSurfacesAttentionWithExpectedCount() {
        Ledger ledger = ledgerAccepting("model/bom-import", 2);

        LedgerEvaluation evaluation = ledger.evaluate(List.of(
                warning("model/bom-import", "rocks-kb"),
                warning("model/bom-import", "komet-claude-plugin"),
                warning("model/bom-import", "new-module")));

        assertThat(evaluation.attention()).hasSize(1);
        assertThat(evaluation.attention().get(0).observed()).isEqualTo(3);
        assertThat(evaluation.attention().get(0).expected()).isEqualTo(2);
        assertThat(evaluation.accepted()).isEmpty();
        assertThat(evaluation.demandsAttention()).isTrue();
    }

    @Test
    void observedBelowExpectedIsAcceptedAndRatchet() {
        Ledger ledger = ledgerAccepting("model/bom-import", 2);

        LedgerEvaluation evaluation = ledger.evaluate(List.of(warning("model/bom-import", "rocks-kb")));

        assertThat(evaluation.accepted()).hasSize(1);
        assertThat(evaluation.ratchet()).hasSize(1);
        assertThat(evaluation.ratchet().get(0).observed()).isEqualTo(1);
        assertThat(evaluation.attention()).isEmpty();
    }

    @Test
    void entryWithNoObservationsIsRatchetAtZero() {
        Ledger ledger = ledgerAccepting("model/bom-import", 2);

        LedgerEvaluation evaluation = ledger.evaluate(List.of());

        assertThat(evaluation.ratchet()).hasSize(1);
        assertThat(evaluation.ratchet().get(0).observed()).isZero();
        assertThat(evaluation.demandsAttention()).isFalse();
    }

    @Test
    void unacceptedWarningIsAttentionWithoutExpectedCount() {
        LedgerEvaluation evaluation = Ledger.empty().evaluate(List.of(
                warning("repository/metadata-resolve-failed/private-assets", "401 rejected")));

        assertThat(evaluation.attention()).hasSize(1);
        assertThat(evaluation.attention().get(0).expected()).isNull();
        assertThat(evaluation.attention().get(0).sample()).contains("401");
        assertThat(evaluation.demandsAttention()).isTrue();
    }

    private Ledger ledgerAccepting(String key, int count) {
        try {
            Path file = tempDir.resolve("ledger-" + key.hashCode() + ".yaml");
            Files.writeString(file, """
                    accepted:
                      - key: %s
                        count: %d
                        reason: test acceptance
                        since: 2026-08-10
                    """.formatted(key, count));
            return Ledger.load(file);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
