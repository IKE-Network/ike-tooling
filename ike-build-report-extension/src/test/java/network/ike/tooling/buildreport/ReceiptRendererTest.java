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
        assertThat(receipt).doesNotContain("FAILURES");
        assertThat(receipt).doesNotContain("ATTENTION");
        assertThat(receipt).doesNotContain("ACCEPTED");
        assertThat(receipt).doesNotContain("RATCHET");
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

        assertThat(receipt).contains("FAILURES");
        assertThat(receipt).contains(
                "- `execution/mojo-failed/maven-compiler-plugin:compile` — komet-desktop: compilation failed");
        assertThat(receipt).contains("ATTENTION");
        assertThat(receipt).contains(
                "- `repository/metadata-resolve-failed/private-assets` — observed 1, not accepted");
        assertThat(receipt).contains(
                "  - occurrence 1/1 — dev.ikm.elk metadata: authentication rejected");
        assertThat(receipt).contains("🔴 failures: 1 · 🟡 attention: 1");
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

        assertThat(receipt).contains("ACCEPTED");
        assertThat(receipt).contains("- `model/bom-import` — expected 2, observed 2 — accepted by design");
        assertThat(receipt).contains("RATCHET");
        assertThat(receipt).contains("- `model/retired-warning` — expected 3, observed 0 — ledger can tighten");
    }

    @Test
    void repositoryAttentionCarriesEvidenceAndRemediation() {
        LedgerEvaluation evaluation = Ledger.empty().evaluate(List.of(
                repositoryFinding("prefixes.txt: HTTP Status: 401"),
                repositoryFinding("maven-metadata.xml: HTTP Status: 401")));

        String receipt = ReceiptRenderer.render("249-SNAPSHOT", STAMP, LedgerMode.GATE, "", evaluation);

        assertThat(receipt).contains(
                "  - repository `spring-release-e22ad65be0b5fee6143d584bba6f2b61b37bb6bf`"
                        + " at `https://repo.spring.io/release`");
        assertThat(receipt).contains(
                "  - declared by the POM of org.springdoc:springdoc-openapi:2.6.0"
                        + " — a dependency, not this workspace");
        assertThat(receipt).contains("  - occurrence 1/2 — prefixes.txt: HTTP Status: 401");
        assertThat(receipt).contains("  - occurrence 2/2 — maven-metadata.xml: HTTP Status: 401");
        assertThat(receipt).contains("## WHAT TO DO");
        assertThat(receipt).contains("<blocked>true</blocked>");
        assertThat(receipt).contains("-Dike.build.report.gate.skip=true");
    }

    @Test
    void aCleanReceiptOffersNoRemediation() {
        String receipt = ReceiptRenderer.render(
                "249-SNAPSHOT", STAMP, LedgerMode.GATE, "gate: clean", Ledger.empty().evaluate(List.of()));

        assertThat(receipt).doesNotContain("## WHAT TO DO");
    }

    @Test
    void buildSectionSaysWhatBuiltAndHowLong() {
        BuildActivity.Overview overview = new BuildActivity.Overview(
                List.of("clean", "install"), List.of("release"), "4.0.0-rc-7", "27", 1,
                java.time.Duration.ofSeconds(252),
                List.of(
                        new BuildActivity.Module("komet-bom", BuildActivity.ModuleResult.BUILT,
                                java.time.Duration.ofMillis(400)),
                        new BuildActivity.Module("komet-desktop", BuildActivity.ModuleResult.BUILT,
                                java.time.Duration.ofSeconds(95))),
                List.of(new BuildActivity.GoalTime("maven-surefire-plugin:test", 2,
                        java.time.Duration.ofSeconds(80))));

        String receipt = ReceiptRenderer.render("262-SNAPSHOT", STAMP, LedgerMode.GATE, "gate: clean",
                Ledger.empty().evaluate(List.of()), overview,
                new ReceiptRenderer.Console(true, List.of(), 0, java.util.Map.of("komet-desktop", 2), List.of()));

        assertThat(receipt).contains("## BUILD");
        assertThat(receipt).contains("> 🟢 gate: clean");
        assertThat(receipt).contains("🟢 **SUCCESS** in 4m 12s — 2 of 2 module(s) built");
        assertThat(receipt).contains("- invocation: `mvn clean install`");
        assertThat(receipt).contains("- profiles: release");
        assertThat(receipt).contains("- environment: Maven 4.0.0-rc-7 · JDK 27 · 1 thread");
        assertThat(receipt).contains("🟢     0.4s  komet-bom\n");
        assertThat(receipt).contains("🟡   1m 35s  komet-desktop  2 warning(s)\n");
        assertThat(receipt).contains("  1m 20s     2×  maven-surefire-plugin:test\n");
        assertThat(receipt.indexOf("## BUILD")).isLessThan(receipt.indexOf("## SUMMARY"));
    }

    @Test
    void aLiveReceiptShowsWhatIsBuildingAndWhatIsPending() {
        BuildActivity.Overview overview = new BuildActivity.Overview(
                List.of("verify"), List.of(), "", "27", 1, java.time.Duration.ofSeconds(80),
                List.of(
                        new BuildActivity.Module("alpha", BuildActivity.ModuleResult.BUILT,
                                java.time.Duration.ofSeconds(70)),
                        new BuildActivity.Module("beta", BuildActivity.ModuleResult.BUILDING,
                                java.time.Duration.ofSeconds(10), "maven-surefire-plugin:test"),
                        new BuildActivity.Module("gamma", BuildActivity.ModuleResult.NOT_BUILT,
                                java.time.Duration.ZERO)),
                List.of());

        String receipt = ReceiptRenderer.renderLive(
                "262-SNAPSHOT", STAMP, overview, ReceiptRenderer.Console.QUIET);

        assertThat(receipt).contains("· live_");
        assertThat(receipt).contains(
                "⏳ **RUNNING** as of 16:30:00, 1m 20s — 1 of 3 module(s) built, 1 building, 1 pending");
        assertThat(receipt).contains(
                "- ⏳ now building: **beta** — maven-surefire-plugin:test, 10.0s so far\n");
        assertThat(receipt).contains("🟢   1m 10s  alpha\n");
        assertThat(receipt).contains("⏳    10.0s… beta  STILL BUILDING — maven-surefire-plugin:test\n");
        assertThat(receipt).contains("⚪        —  gamma  pending\n");
        assertThat(receipt).doesNotContain("## SUMMARY");
    }

    @Test
    void aFailedBuildNamesTheModulesItDidNotReach() {
        BuildActivity.Overview overview = new BuildActivity.Overview(
                List.of("verify"), List.of(), "", "27", 4, java.time.Duration.ofSeconds(9),
                List.of(
                        new BuildActivity.Module("alpha", BuildActivity.ModuleResult.FAILED,
                                java.time.Duration.ofSeconds(8)),
                        new BuildActivity.Module("beta", BuildActivity.ModuleResult.NOT_BUILT,
                                java.time.Duration.ZERO)),
                List.of());

        String receipt = ReceiptRenderer.render("262-SNAPSHOT", STAMP, LedgerMode.REPORT, "",
                Ledger.empty().evaluate(List.of()), overview, ReceiptRenderer.Console.QUIET);

        assertThat(receipt).contains("🔴 **FAILED** in 9.0s — 0 of 2 module(s) built, 1 failed, 1 not reached");
        assertThat(receipt).contains("🔴     8.0s  alpha  FAILED\n");
        assertThat(receipt).contains("⚪        —  beta  not reached\n");
        assertThat(receipt).contains("4 threads");
        assertThat(receipt).doesNotContain("|---");
        assertThat(receipt).contains("times overlap");
    }

    @Test
    void consoleWarningsFoldToOneLineWithCountAndOrigin() {
        ReceiptRenderer.Console console = ReceiptRenderer.Console.of(true, List.of(incubator()), 0, List.of());

        String receipt = ReceiptRenderer.render("262-SNAPSHOT", STAMP, LedgerMode.GATE, "gate: clean",
                Ledger.empty().evaluate(List.of()), null, console);

        assertThat(receipt).contains("## 🟡 CONSOLE");
        assertThat(receipt).contains("5 warning and error line(s) folded into 1 distinct message(s)");
        assertThat(receipt).contains(
                "- 🟡 **5×** `[stderr] WARNING: Using incubator modules: jdk.incubator.vector`"
                        + " — maven-surefire-plugin:test · 5 modules: a, b, c, … +2");
        assertThat(receipt).contains("· console: 0 error(s), 🟡 5 warning(s)");
        assertThat(receipt).doesNotContain("## WHAT TO DO");
    }

    @Test
    void anUncapturedConsoleIsSaidSoRatherThanShownAsQuiet() {
        String receipt = ReceiptRenderer.render("262-SNAPSHOT", STAMP, LedgerMode.REPORT, "",
                Ledger.empty().evaluate(List.of()), null,
                new ReceiptRenderer.Console(false, List.of(), 0, java.util.Map.of(), List.of()));

        assertThat(receipt).contains("Console warnings were not captured");
        assertThat(receipt).contains("· console: not captured");
    }

    @Test
    void anIgnoredWarningIsShownButCountsNowhere() {
        ReceiptRenderer.Console console = ReceiptRenderer.Console.of(true,
                List.of(incubator(),
                        new ConsoleMessages.Item(ConsoleMessages.Level.ERROR,
                                "COMPILATION ERROR :", 1, java.util.Map.of("a", 1), List.of()),
                        new ConsoleMessages.Item(ConsoleMessages.Level.ERROR,
                                "[jlink] Using incubator modules, relayed at error level", 2,
                                java.util.Map.of(), List.of())),
                0,
                List.of(new ConsoleIgnore("Using incubator modules", "JVM notice"),
                        new ConsoleIgnore("long gone", "")));

        assertThat(console.moduleWarnings()).isEmpty();
        assertThat(console.items()).extracting(ConsoleMessages.Item::level)
                .containsExactly(ConsoleMessages.Level.ERROR);

        String receipt = ReceiptRenderer.render("262-SNAPSHOT", STAMP, LedgerMode.GATE, "gate: clean",
                Ledger.empty().evaluate(List.of()), null, console);

        assertThat(receipt).contains("- 🔵 **7×** `Using incubator modules` — JVM notice");
        assertThat(receipt).contains("- 🔵 **0×** `long gone` — not seen this session");
        assertThat(receipt).contains("· console: 🔴 1 error(s), 0 warning(s), 7 ignored");
    }

    @Test
    void foldedKindsRenderAsOneEntryWithTheirHeaviestMessages() {
        java.util.List<ConsoleMessages.Item> items = new java.util.ArrayList<>();
        for (int index = 1; index <= 5; index++) {
            items.add(new ConsoleMessages.Item(ConsoleMessages.Level.WARNING,
                    "Thing" + index + " has been deprecated", 60 / index,
                    java.util.Map.of("kview", 60 / index), List.of("maven-compiler-plugin:compile"),
                    "compiler: deprecation", 30 / index));
        }
        ReceiptRenderer.Console console = ReceiptRenderer.Console.of(true, items, 0, List.of());

        String receipt = ReceiptRenderer.render("262-SNAPSHOT", STAMP, LedgerMode.GATE, "gate: clean",
                Ledger.empty().evaluate(List.of()), null, console);

        assertThat(receipt).contains("- 🟡 **137×** compiler: deprecation — 5 distinct · kview 137\n");
        assertThat(receipt).contains("  - **60×** `Thing1 has been deprecated` — 30 files\n");
        assertThat(receipt).contains("  - … and 2 more\n");
        assertThat(receipt).doesNotContain("Thing4");
    }

    private static ConsoleMessages.Item incubator() {
        java.util.Map<String, Integer> modules = new java.util.LinkedHashMap<>();
        for (String module : List.of("a", "b", "c", "d", "e")) {
            modules.put(module, 1);
        }
        return new ConsoleMessages.Item(ConsoleMessages.Level.WARNING,
                "[stderr] WARNING: Using incubator modules: jdk.incubator.vector", 5,
                modules, List.of("maven-surefire-plugin:test"));
    }

    private static Finding repositoryFinding(String detail) {
        return new Finding(
                FindingCategory.REPOSITORY, Severity.WARNING,
                "repository/metadata-resolve-failed/spring-release", detail,
                new java.util.LinkedHashMap<>(java.util.Map.of(
                        Finding.CONTEXT_REPOSITORY_ID,
                        "spring-release-e22ad65be0b5fee6143d584bba6f2b61b37bb6bf",
                        Finding.CONTEXT_REPOSITORY_URL, "https://repo.spring.io/release",
                        Finding.CONTEXT_DECLARED_BY,
                        "declared by the POM of org.springdoc:springdoc-openapi:2.6.0"
                                + " — a dependency, not this workspace")));
    }

    @Test
    void ledgerNoteRendersAsBlockquote() {
        LedgerEvaluation evaluation = Ledger.empty().evaluate(List.of());

        String receipt = ReceiptRenderer.render(
                "244-SNAPSHOT", STAMP, LedgerMode.REPORT,
                "ledger .mvn/build-report.yaml not used: mode must be report or gate, was: enforce",
                evaluation);

        assertThat(receipt).contains("> 🟡 ledger .mvn/build-report.yaml not used: mode must be report or gate");
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
