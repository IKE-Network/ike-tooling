package network.ike.tooling.buildreport;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The receipt's MEASURES section and the measure findings around it.
 */
class MeasuresReceiptTest {

    private static final ZonedDateTime STAMP =
            ZonedDateTime.of(2026, 10, 9, 12, 0, 0, 0, ZoneId.of("America/Los_Angeles"));

    @Test
    void measuresRenderAsRowsBoundsAndPublication() {
        Map<String, Double> values = new LinkedHashMap<>();
        values.put(MeasureKey.WARNINGS_TOTAL.key(), 800.0);
        values.put(MeasureKey.WARNINGS_COMPILER_DEPRECATION.key(), 415.0);
        values.put(MeasureKey.WARNINGS_STDERR.key(), 200.0);
        values.put(MeasureKey.COVERAGE_LINE_PERCENT.key(), 41.2);
        values.put(MeasureKey.COVERAGE_LINE_COVERED.key(), 12345.0);
        values.put(MeasureKey.COVERAGE_LINE_TOTAL.key(), 29960.0);
        values.put("size.image", 1_300_000.0);
        values.put("bench.rocks.scan.median.micros", 1234.0);
        Measures measures = new Measures(values, Map.of(),
                List.of(new Measures.SlowSuite("entity", "dev.ikm.SlowIT", 125.0)),
                List.of("tests: no surefire or failsafe reports in this session"));
        Ledger ledger = Ledger.of(LedgerMode.GATE, List.of(), List.of(), List.of(
                new MeasureEntry("warnings.total", MeasureEntry.Bound.AT_MOST, 900.0),
                new MeasureEntry("coverage.line.percent", MeasureEntry.Bound.AT_LEAST, 45.0),
                new MeasureEntry("tests.skipped", MeasureEntry.Bound.AT_MOST, 0.0)), List.of());
        List<MeasureStatus> statuses = ledger.evaluateMeasures(measures);
        LedgerEvaluation evaluation = ledger.evaluate(Ledger.measureFindings(statuses));

        String receipt = ReceiptRenderer.render("264-SNAPSHOT", STAMP, LedgerMode.GATE, "", evaluation,
                null, ReceiptRenderer.Console.QUIET, MeasureReport.of(measures, statuses).withPublished(3));

        assertThat(receipt).contains("## 🟡 MEASURES");
        assertThat(receipt).contains("      800  warnings — 415 deprecation · 200 stderr\n");
        assertThat(receipt).contains("    41.2%  lines covered — 12345 of 29960\n");
        assertThat(receipt).contains("  1.2 MiB  size.image\n");
        assertThat(receipt).contains("     1234  bench.rocks.scan.median.micros\n");
        assertThat(receipt).contains("   2m 05s  entity · dev.ikm.SlowIT\n");
        assertThat(receipt).contains("- 🔵 `warnings.total` 800 within at-most 900 — can tighten to 800\n");
        assertThat(receipt).contains("- 🔴 `coverage.line.percent` 41.2 — outside at-least 45, see ATTENTION\n");
        assertThat(receipt).contains("- ⚪ `tests.skipped` not measured this session (at-most 0)\n");
        assertThat(receipt).contains("Published to TeamCity as 3 build statistic(s).");
        assertThat(receipt).contains("- tests: no surefire or failsafe reports in this session");
        assertThat(receipt).contains(
                "- `measure/coverage.line.percent` — coverage.line.percent is 41.2, below the ledger's at-least 45\n");
        assertThat(receipt).contains("Measure findings are a session value outside a bound");
        assertThat(receipt).contains("🟡 attention: 1");
    }

    @Test
    void withoutAMeasureReportTheSectionIsAbsent() {
        LedgerEvaluation evaluation = Ledger.empty().evaluate(List.of());

        String receipt = ReceiptRenderer.render("264-SNAPSHOT", STAMP, LedgerMode.REPORT, "", evaluation,
                null, ReceiptRenderer.Console.QUIET);

        assertThat(receipt).doesNotContain("MEASURES");
    }

    @Test
    void aReportWithNothingInItRendersNothing() {
        LedgerEvaluation evaluation = Ledger.empty().evaluate(List.of());

        String receipt = ReceiptRenderer.render("264-SNAPSHOT", STAMP, LedgerMode.REPORT, "", evaluation,
                null, ReceiptRenderer.Console.QUIET, MeasureReport.of(Measures.empty(), List.of()));

        assertThat(receipt).doesNotContain("MEASURES");
    }
}
