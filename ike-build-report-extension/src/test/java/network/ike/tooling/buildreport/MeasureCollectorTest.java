package network.ike.tooling.buildreport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Measure collection from the files a session leaves behind and the
 * console it heard.
 */
class MeasureCollectorTest {

    @TempDir
    Path tempDir;

    private static ConsoleMessages.Item warning(String message, String kind, String goal) {
        return new ConsoleMessages.Item(ConsoleMessages.Level.WARNING, message, 1,
                Map.of("alpha", 1), List.of(goal), kind, 0);
    }

    private static String suite(String name, int tests, int failures, int errors, int skipped, String time) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<testsuite name=\"" + name + "\" time=\"" + time + "\" tests=\"" + tests
                + "\" errors=\"" + errors + "\" skipped=\"" + skipped + "\" failures=\"" + failures + "\">\n"
                + "  <testcase name=\"one\" classname=\"" + name + "\" time=\"0.1\"/>\n"
                + "</testsuite>\n";
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test
    void everyConsoleWarningFilesUnderOneKind() {
        assertThat(MeasureCollector.warningKind(warning("Foo has been deprecated", "compiler: deprecation",
                "maven-compiler-plugin:compile"))).isEqualTo(MeasureKey.WARNINGS_COMPILER_DEPRECATION);
        assertThat(MeasureCollector.warningKind(warning("Foo marked for removal", "compiler: removal",
                "maven-compiler-plugin:compile"))).isEqualTo(MeasureKey.WARNINGS_COMPILER_REMOVAL);
        assertThat(MeasureCollector.warningKind(warning("raw type", "compiler: rawtypes",
                "maven-compiler-plugin:compile"))).isEqualTo(MeasureKey.WARNINGS_COMPILER_OTHER);
        assertThat(MeasureCollector.warningKind(warning("g:a:jar:1:compile",
                "dependency analysis: Unused declared dependencies", "maven-dependency-plugin:analyze")))
                .isEqualTo(MeasureKey.WARNINGS_DEPENDENCY);
        assertThat(MeasureCollector.warningKind(warning("SomeTest — 2 of 5 skipped", "tests: skipped",
                "maven-surefire-plugin:test"))).isEqualTo(MeasureKey.WARNINGS_TESTS);
        assertThat(MeasureCollector.warningKind(warning("[stderr] WARNING: something", "",
                "maven-surefire-plugin:test"))).isEqualTo(MeasureKey.WARNINGS_STDERR);
        assertThat(MeasureCollector.warningKind(warning("no comment", "", "maven-javadoc-plugin:jar")))
                .isEqualTo(MeasureKey.WARNINGS_JAVADOC);
        assertThat(MeasureCollector.warningKind(warning("something else", "", "some-plugin:goal")))
                .isEqualTo(MeasureKey.WARNINGS_OTHER);
    }

    @Test
    void warningsAreCountedByKindFromTheConsoleThatCounts() {
        List<ConsoleMessages.Item> items = List.of(
                new ConsoleMessages.Item(ConsoleMessages.Level.WARNING, "Foo has been deprecated", 7,
                        Map.of("alpha", 7), List.of("maven-compiler-plugin:compile"), "compiler: deprecation", 3),
                warning("[stderr] WARNING: noise", "", "maven-surefire-plugin:test"),
                new ConsoleMessages.Item(ConsoleMessages.Level.ERROR, "boom", 2, Map.of(), List.of()));
        ReceiptRenderer.Console console = ReceiptRenderer.Console.of(true, items, 0,
                List.of(new ConsoleIgnore("noise", "ignored")));

        Measures measures = MeasureCollector.collect(tempDir, List.of(), null, console, Ledger.empty());

        assertThat(measures.value(MeasureKey.WARNINGS_TOTAL)).isEqualTo(7.0);
        assertThat(measures.value(MeasureKey.WARNINGS_COMPILER_DEPRECATION)).isEqualTo(7.0);
        assertThat(measures.value(MeasureKey.WARNINGS_STDERR)).isEqualTo(0.0);
        assertThat(measures.value(MeasureKey.ERRORS_CONSOLE)).isEqualTo(2.0);
        assertThat(measures.has(MeasureKey.TESTS_RUN)).isFalse();
        assertThat(measures.has(MeasureKey.COVERAGE_LINE_PERCENT)).isFalse();
        assertThat(measures.notes()).anySatisfy(note -> assertThat(note).contains("no surefire or failsafe reports"));
        assertThat(measures.notes()).anySatisfy(note -> assertThat(note).contains("no JaCoCo execution data"));
    }

    @Test
    void anUncapturedConsoleLeavesWarningsUnmeasured() {
        ReceiptRenderer.Console console = ReceiptRenderer.Console.of(false, List.of(), 0, List.of());

        Measures measures = MeasureCollector.collect(tempDir, List.of(), null, console, Ledger.empty());

        assertThat(measures.has(MeasureKey.WARNINGS_TOTAL)).isFalse();
        assertThat(measures.notes()).anySatisfy(note -> assertThat(note).contains("console was not captured"));
    }

    @Test
    void testReportsSumAcrossModulesAndNameTheSlowestClasses() throws IOException {
        Path alpha = tempDir.resolve("alpha");
        Path beta = tempDir.resolve("beta");
        write(alpha.resolve("target/surefire-reports/TEST-alpha.FastTest.xml"), suite("alpha.FastTest", 3, 0, 0, 1, "0.5"));
        write(alpha.resolve("target/surefire-reports/TEST-alpha.SlowTest.xml"), suite("alpha.SlowTest", 2, 1, 0, 0, "12.25"));
        write(alpha.resolve("target/surefire-reports/not-a-report.xml"), "<other/>");
        write(beta.resolve("target/failsafe-reports/TEST-beta.StoreIT.xml"), suite("beta.StoreIT", 4, 0, 1, 2, "70.0"));
        List<ModuleLocation> modules = List.of(
                ModuleLocation.standard("alpha", alpha), ModuleLocation.standard("beta", beta),
                ModuleLocation.standard("gamma", tempDir.resolve("gamma")));

        Measures measures = MeasureCollector.collect(
                tempDir, modules, null, ReceiptRenderer.Console.QUIET, Ledger.empty());

        assertThat(measures.value(MeasureKey.TESTS_RUN)).isEqualTo(9.0);
        assertThat(measures.value(MeasureKey.TESTS_FAILED)).isEqualTo(2.0);
        assertThat(measures.value(MeasureKey.TESTS_SKIPPED)).isEqualTo(3.0);
        assertThat(measures.value(MeasureKey.TESTS_SECONDS)).isEqualTo(82.75);
        assertThat(measures.modules()).containsOnlyKeys("alpha", "beta");
        assertThat(measures.modules().get("beta").get(MeasureKey.TESTS_RUN.key())).isEqualTo(4.0);
        assertThat(measures.slowest()).extracting(Measures.SlowSuite::suite)
                .containsExactly("beta.StoreIT", "alpha.SlowTest", "alpha.FastTest");
        assertThat(measures.slowest().get(0).module()).isEqualTo("beta");
    }

    @Test
    void measurementsBecomeMeasuresUnderTheKeysTheTestWrote() throws IOException {
        Path alpha = tempDir.resolve("alpha");
        Path beta = tempDir.resolve("beta");
        write(alpha.resolve("target/measurements/store-benchmark.properties"),
                "bench.rocks.scan.median.micros=1234\nbench.rocks.scan.count=16165\nagents=none\n");
        write(beta.resolve("target/measurements/other.properties"),
                "bench.rocks.scan.count=99\nimport.seconds=42.5\n");
        List<ModuleLocation> modules = List.of(
                ModuleLocation.standard("alpha", alpha), ModuleLocation.standard("beta", beta));

        Measures measures = MeasureCollector.collect(
                tempDir, modules, null, ReceiptRenderer.Console.QUIET, Ledger.empty());

        assertThat(measures.value("bench.rocks.scan.median.micros")).isEqualTo(1234.0);
        assertThat(measures.value("bench.rocks.scan.count")).isEqualTo(16165.0);
        assertThat(measures.value("import.seconds")).isEqualTo(42.5);
        assertThat(measures.value("agents")).isNull();
        assertThat(measures.notes()).anySatisfy(note ->
                assertThat(note).contains("bench.rocks.scan.count").contains("beta/other.properties"));
    }

    @Test
    void sizesSumWhatAGlobMatchesAndNoteWhatMatchesNothing() throws IOException {
        write(tempDir.resolve("dist/komet.pkg"), "x".repeat(100));
        write(tempDir.resolve("dist/komet.msi"), "x".repeat(50));
        write(tempDir.resolve("dist/image/bin/java"), "x".repeat(30));
        write(tempDir.resolve("dist/image/lib/modules"), "x".repeat(70));
        Ledger ledger = Ledger.of(LedgerMode.REPORT, List.of(), List.of(), List.of(), List.of(
                new SizeEntry("size.installers", "dist/*.{pkg,msi}"),
                new SizeEntry("size.image", "dist/image"),
                new SizeEntry("size.image.libs", "dist/image/lib/**"),
                new SizeEntry("size.missing", "dist/none/*.zip")));

        Measures measures = MeasureCollector.collect(
                tempDir, List.of(), null, ReceiptRenderer.Console.QUIET, ledger);

        assertThat(measures.value("size.installers")).isEqualTo(150.0);
        assertThat(measures.value("size.image")).isEqualTo(100.0);
        assertThat(measures.value("size.image.libs")).isEqualTo(70.0);
        assertThat(measures.value("size.missing")).isNull();
        assertThat(measures.notes()).anySatisfy(note -> assertThat(note).contains("size.missing"));
    }

    @Test
    void theBuildOverviewContributesTimeAndModuleCounts() {
        BuildActivity activity = new BuildActivity();
        activity.sessionStarted(List.of("alpha", "beta"), List.of("verify"), List.of(), "4.0.0", 4);
        activity.projectStarted("alpha");
        activity.projectFinished("alpha", BuildActivity.ModuleResult.BUILT);
        activity.projectStarted("beta");
        activity.projectFinished("beta", BuildActivity.ModuleResult.FAILED);

        Measures measures = MeasureCollector.collect(
                tempDir, List.of(), activity.snapshot(), ReceiptRenderer.Console.QUIET, Ledger.empty());

        assertThat(measures.value(MeasureKey.BUILD_MODULES_BUILT)).isEqualTo(1.0);
        assertThat(measures.value(MeasureKey.BUILD_MODULES_FAILED)).isEqualTo(1.0);
        assertThat(measures.value(MeasureKey.BUILD_THREADS)).isEqualTo(4.0);
        assertThat(measures.value(MeasureKey.BUILD_SECONDS)).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    void collectedValuesKeepInsertionOrderAndMeasuresAreImmutable() {
        Map<String, Double> values = new LinkedHashMap<>();
        values.put("b", 1.0);
        values.put("a", 2.0);
        Measures measures = new Measures(values, Map.of(), new ArrayList<>(), List.of());
        values.put("c", 3.0);

        assertThat(measures.values().keySet()).containsExactly("b", "a");
    }
}
