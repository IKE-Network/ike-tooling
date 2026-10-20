package network.ike.tooling.buildreport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Measure bounds in the ledger: parsing, evaluation, gating, the
 * ratchet, the canonical writer and the sidecar (ike-issues#1207).
 */
class MeasureBoundsTest {

    @TempDir
    Path tempDir;

    private Ledger load(String yaml) throws IOException {
        Path file = tempDir.resolve("ledger-" + Math.abs(yaml.hashCode()) + ".yaml");
        Files.writeString(file, yaml);
        return Ledger.load(file);
    }

    private static Measures measures(Map<String, Double> values) {
        return new Measures(values, Map.of(), List.of(), List.of());
    }

    @Test
    void boundsParseWithEitherDirectionSlackReasonDateAndMode() throws IOException {
        Ledger ledger = load("""
                mode: gate
                measures:
                  - key: warnings.compiler.deprecation
                    at-most: 415
                    reason: the October 2026 baseline
                    since: 2026-10-09
                  - key: coverage.line.percent
                    at-least: 38.5
                    slack: 0.5
                    mode: report
                sizes:
                  - key: size.image
                    path: "dist/image/**"
                """);

        assertThat(ledger.measures()).containsExactly(
                new MeasureEntry("warnings.compiler.deprecation", MeasureEntry.Bound.AT_MOST, 415.0, 0.0,
                        "the October 2026 baseline", "2026-10-09", null),
                new MeasureEntry("coverage.line.percent", MeasureEntry.Bound.AT_LEAST, 38.5, 0.5,
                        "", "", LedgerMode.REPORT));
        assertThat(ledger.sizes()).containsExactly(new SizeEntry("size.image", "dist/image/**"));
        assertThat(ledger.measures().get(0).describe()).isEqualTo("at-most 415");
        assertThat(ledger.measures().get(1).describe()).isEqualTo("at-least 38.5");
    }

    @Test
    void aBoundNeedsExactlyOneNumericDirection() {
        assertThatThrownBy(() -> load("measures:\n  - key: a\n    at-most: 1\n    at-least: 1\n"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("both");
        assertThatThrownBy(() -> load("measures:\n  - key: a\n    reason: none\n"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("needs at-most or at-least");
        assertThatThrownBy(() -> load("measures:\n  - key: a\n    at-most: many\n"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must be a number");
        assertThatThrownBy(() -> load("sizes:\n  - key: a\n"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("key and a path");
    }

    @Test
    void aViolatedBoundIsAnAttentionFindingThatGates() throws IOException {
        Ledger ledger = load("mode: gate\nmeasures:\n  - key: tests.skipped\n    at-most: 10\n");

        List<MeasureStatus> statuses = ledger.evaluateMeasures(measures(Map.of("tests.skipped", 12.0)));
        List<Finding> findings = Ledger.measureFindings(statuses);
        LedgerEvaluation evaluation = ledger.evaluate(findings);

        assertThat(statuses).singleElement().satisfies(status -> {
            assertThat(status.measured()).isTrue();
            assertThat(status.violated()).isTrue();
            assertThat(status.canTighten()).isFalse();
        });
        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.category()).isEqualTo(FindingCategory.MEASURE);
            assertThat(finding.key()).isEqualTo("measure/tests.skipped");
            assertThat(finding.detail()).isEqualTo("tests.skipped is 12, above the ledger's at-most 10");
            assertThat(finding.context()).containsEntry(MeasureStatus.CONTEXT_OBSERVED, "12")
                    .containsEntry(MeasureStatus.CONTEXT_BOUND, "at-most 10");
        });
        assertThat(evaluation.attention()).singleElement().extracting(LedgerEvaluation.AttentionItem::key)
                .isEqualTo("measure/tests.skipped");
        assertThat(ledger.gatingAttention(evaluation)).hasSize(1);
        assertThat(MeasureStatus.measureKey("measure/tests.skipped")).isEqualTo("tests.skipped");
        assertThat(MeasureStatus.measureKey("model/other")).isNull();
    }

    @Test
    void aBoundInReportModeIsAttentionButDoesNotGate() throws IOException {
        Ledger ledger = load("mode: gate\nmeasures:\n  - key: coverage.line.percent\n    at-least: 50\n    mode: report\n");

        List<MeasureStatus> statuses = ledger.evaluateMeasures(measures(Map.of("coverage.line.percent", 41.2)));
        LedgerEvaluation evaluation = ledger.evaluate(Ledger.measureFindings(statuses));

        assertThat(evaluation.attention()).hasSize(1);
        assertThat(evaluation.attention().get(0).sample())
                .isEqualTo("coverage.line.percent is 41.2, below the ledger's at-least 50");
        assertThat(ledger.gatingAttention(evaluation)).isEmpty();
    }

    @Test
    void anUnmeasuredBoundIsNeitherPassedNorViolated() throws IOException {
        Ledger ledger = load("measures:\n  - key: tests.skipped\n    at-most: 10\n");

        List<MeasureStatus> statuses = ledger.evaluateMeasures(Measures.empty());

        assertThat(statuses).singleElement().satisfies(status -> {
            assertThat(status.measured()).isFalse();
            assertThat(status.violated()).isFalse();
            assertThat(status.canTighten()).isFalse();
            assertThat(status.finding()).isNull();
        });
        assertThat(Ledger.measureFindings(statuses)).isEmpty();
    }

    @Test
    void theRatchetTightensEachDirectionKeepingSlackAndLeavesTheRestAlone() throws IOException {
        Ledger ledger = load("""
                measures:
                  - key: warnings.total
                    at-most: 20
                    slack: 2
                  - key: coverage.line.percent
                    at-least: 30
                    slack: 0.5
                  - key: tests.skipped
                    at-most: 5
                  - key: build.seconds
                    at-most: 300
                """);
        Map<String, Double> measured = Map.of(
                "warnings.total", 15.0,          // tightens to 17
                "coverage.line.percent", 41.2,  // tightens to 40.7
                "tests.skipped", 9.0);          // violated: untouched
                                                // build.seconds unmeasured: untouched

        RatchetPlanner.Plan plan = RatchetPlanner.plan(ledger, Map.of(), measured);

        assertThat(plan.changesAnything()).isTrue();
        assertThat(plan.tightenings()).isEmpty();
        assertThat(plan.measureTightenings()).extracting(RatchetPlanner.MeasureTightening::limit)
                .containsExactly(17.0, 40.7);
        assertThat(plan.result().measures()).extracting(MeasureEntry::limit)
                .containsExactly(17.0, 40.7, 5.0, 300.0);
        assertThat(RatchetPlanner.plan(plan.result(), Map.of(), measured).changesAnything()).isFalse();
    }

    @Test
    void theWriterRoundTripsBoundsAndSizes() throws IOException {
        Ledger ledger = load("""
                mode: gate
                accepted:
                  - key: model/bom-import
                    count: 2
                    reason: accepted by design
                measures:
                  - key: warnings.compiler.deprecation
                    at-most: 415
                    reason: the October 2026 baseline, counted by hand from the log of the clean-up
                    since: 2026-10-09
                  - key: coverage.line.percent
                    at-least: 38.5
                    slack: 0.5
                    mode: report
                sizes:
                  - key: size.image
                    path: "dist/image/**"
                console:
                  ignore:
                    - match: "Using incubator modules"
                """);

        Path rewritten = tempDir.resolve("rewritten.yaml");
        LedgerWriter.write(rewritten, ledger);
        Ledger reloaded = Ledger.load(rewritten);

        assertThat(reloaded.measures()).isEqualTo(ledger.measures());
        assertThat(reloaded.sizes()).isEqualTo(ledger.sizes());
        assertThat(reloaded.entries()).isEqualTo(ledger.entries());
        assertThat(reloaded.consoleIgnores()).isEqualTo(ledger.consoleIgnores());
        assertThat(Files.readString(rewritten)).contains("    at-most: 415\n").contains("    at-least: 38.5\n")
                .contains("    slack: 0.5\n").contains("    path: \"dist/image/**\"\n");
    }

    @Test
    void theSidecarCarriesMeasuresBesideObservedCounts() throws IOException {
        Path sidecar = tempDir.resolve("target/build-report-observations.yaml");
        Finding warning = new Finding(FindingCategory.MODEL, Severity.WARNING, "model/thing", "detail");
        LedgerEvaluation evaluation = Ledger.empty().evaluate(List.of(warning));
        Measures measures = new Measures(
                Map.of("warnings.total", 800.0, "coverage.line.percent", 41.25),
                Map.of("entity", Map.of("tests.run", 300.0)),
                List.of(), List.of());

        ObservationsFile.write(sidecar, evaluation, List.of(warning), measures);

        assertThat(ObservationsFile.readObserved(sidecar)).containsExactly(Map.entry("model/thing", 1L));
        assertThat(ObservationsFile.readMeasures(sidecar))
                .containsEntry("warnings.total", 800.0).containsEntry("coverage.line.percent", 41.25);
        assertThat(Files.readString(sidecar)).contains("modules:\n  \"entity\":\n    \"tests.run\": 300\n");

        ObservationsFile.write(sidecar, evaluation, List.of(warning));
        assertThat(ObservationsFile.readMeasures(sidecar)).isEmpty();
        assertThat(ObservationsFile.readMeasures(tempDir.resolve("absent.yaml"))).isEmpty();
    }
}
