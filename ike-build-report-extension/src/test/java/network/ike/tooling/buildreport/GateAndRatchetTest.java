package network.ike.tooling.buildreport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2 semantics — gating filters, canonical ledger round-trip,
 * downward-only ratchet planning, and the observations sidecar — on
 * real files and real evaluations.
 */
class GateAndRatchetTest {

    @TempDir
    Path tempDir;

    private static Finding warning(String key) {
        return new Finding(FindingCategory.MODEL, Severity.WARNING, key, "detail");
    }

    private Ledger load(String yaml) throws IOException {
        Path file = tempDir.resolve("ledger-" + Math.abs(yaml.hashCode()) + ".yaml");
        Files.writeString(file, yaml);
        try {
            return Ledger.load(file);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void unacceptedFindingsAlwaysGate() throws IOException {
        Ledger ledger = load("mode: gate\n");

        LedgerEvaluation evaluation = ledger.evaluate(List.of(warning("model/new-thing")));
        List<LedgerEvaluation.AttentionItem> gating = ledger.gatingAttention(evaluation);

        assertThat(gating).hasSize(1);
        GateVerdict verdict = new GateVerdict(ledger.mode(), gating, false, false, null);
        assertThat(verdict.shouldFailBuild()).isTrue();
    }

    @Test
    void entryModeReportExemptsOverCountsFromGating() throws IOException {
        Ledger ledger = load("""
                mode: gate
                accepted:
                  - key: model/onboarding
                    count: 1
                    mode: report
                """);

        LedgerEvaluation evaluation = ledger.evaluate(List.of(
                warning("model/onboarding"), warning("model/onboarding")));

        assertThat(evaluation.attention()).hasSize(1);
        assertThat(ledger.gatingAttention(evaluation)).isEmpty();
        GateVerdict verdict = new GateVerdict(
                ledger.mode(), ledger.gatingAttention(evaluation), false, false, null);
        assertThat(verdict.shouldFailBuild()).isFalse();
    }

    @Test
    void skipAndAlreadyFailedAndReportModeNeverFail() {
        LedgerEvaluation.AttentionItem item =
                new LedgerEvaluation.AttentionItem("k", 1, null, "s");

        assertThat(new GateVerdict(LedgerMode.GATE, List.of(item), false, true, null)
                .shouldFailBuild()).isFalse(); // escape hatch
        assertThat(new GateVerdict(LedgerMode.GATE, List.of(item), true, false, null)
                .shouldFailBuild()).isFalse(); // build already failed
        assertThat(new GateVerdict(LedgerMode.REPORT, List.of(item), false, false, null)
                .shouldFailBuild()).isFalse(); // report mode
        assertThat(new GateVerdict(LedgerMode.GATE, List.of(item), false, false, null)
                .shouldFailBuild()).isTrue();  // armed and gating
    }

    @Test
    void ledgerWriterRoundTripsAllFields() throws IOException {
        Ledger original = Ledger.of(LedgerMode.GATE, List.of(
                new AcceptedEntry("model-problem/bom-import-from-reactor", 10,
                        "accepted by design — the workspace BOM is a living member of the "
                                + "working set and the derived count is the number of record",
                        "2026-08-11", null),
                new AcceptedEntry("repository/metadata-resolve-failed/some-repo", 2,
                        "onboarding", "2026-08-11", LedgerMode.REPORT)));

        Path file = tempDir.resolve("round-trip.yaml");
        LedgerWriter.write(file, original);
        Ledger reloaded = Ledger.load(file);

        assertThat(reloaded.mode()).isEqualTo(original.mode());
        assertThat(reloaded.entries()).isEqualTo(original.entries());
    }

    @Test
    void ratchetTightensDownwardOnlyAndKeepsZeroEntriesAsTripwires() {
        Ledger ledger = Ledger.of(LedgerMode.REPORT, List.of(
                new AcceptedEntry("model/shrank", 9, "r", "2026-08-11", null),
                new AcceptedEntry("model/steady", 2, "r", "2026-08-11", null),
                new AcceptedEntry("model/grew", 1, "r", "2026-08-11", null),
                new AcceptedEntry("model/gone", 3, "r", "2026-08-11", null)));
        Map<String, Long> observed = Map.of(
                "model/shrank", 4L,
                "model/steady", 2L,
                "model/grew", 5L);

        RatchetPlanner.Plan plan = RatchetPlanner.plan(ledger, observed);

        assertThat(plan.changesAnything()).isTrue();
        assertThat(plan.tightenings()).extracting(t -> t.entry().key())
                .containsExactly("model/shrank", "model/gone");
        List<AcceptedEntry> result = plan.result().entries();
        assertThat(result).extracting(AcceptedEntry::count)
                .containsExactly(4, 2, 1, 0); // shrank→4, steady kept, grew NOT loosened, gone→0 tripwire
        assertThat(result).extracting(AcceptedEntry::reason)
                .allMatch(r -> r.equals("r")); // data survives
    }

    @Test
    void observationsSidecarRoundTrips() throws IOException {
        Path file = tempDir.resolve("target/build-report-observations.yaml");
        LedgerEvaluation evaluation = Ledger.empty().evaluate(List.of(
                warning("model/a"), warning("model/a"), warning("repo/b")));

        ObservationsFile.write(file, evaluation, List.of(
                warning("model/a"), warning("model/a"), warning("repo/b")));
        Map<String, Long> observed = ObservationsFile.readObserved(file);

        assertThat(observed).containsExactly(
                Map.entry("model/a", 2L), Map.entry("repo/b", 1L));
        assertThat(ObservationsFile.readObserved(tempDir.resolve("absent.yaml"))).isEmpty();
    }
}
