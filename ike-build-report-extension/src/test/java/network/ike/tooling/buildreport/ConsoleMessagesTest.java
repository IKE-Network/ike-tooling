package network.ike.tooling.buildreport;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Console consolidation: what is kept, what is folded, what is ignored.
 */
class ConsoleMessagesTest {

    private static final String INCUBATOR =
            "[stderr] WARNING: Using incubator modules: jdk.incubator.vector";

    private final ConsoleMessages console = new ConsoleMessages();

    @Test
    void aWarningRepeatedPerModuleFoldsToOneItem() {
        for (String module : List.of("alpha", "beta", "gamma")) {
            console.accept("[WARNING] " + INCUBATOR,
                    new BuildActivity.Context(module, "maven-surefire-plugin:test"), "");
        }

        assertThat(console.snapshot()).containsExactly(new ConsoleMessages.Item(
                ConsoleMessages.Level.WARNING, INCUBATOR, 3,
                java.util.Map.of("alpha", 1, "beta", 1, "gamma", 1),
                List.of("maven-surefire-plugin:test")));
    }

    @Test
    void terminalStylingIsStrippedBeforeMatching() {
        console.accept("[\u001B[1;33mWARNING\u001B[m] " + INCUBATOR, BuildActivity.Context.NONE, "");

        assertThat(console.snapshot()).extracting(ConsoleMessages.Item::message).containsExactly(INCUBATOR);
    }

    @Test
    void infoLinesBlankWarningsAndOwnLinesAreIgnored() {
        console.accept("[INFO] Building alpha 1", BuildActivity.Context.NONE, "");
        console.accept("[WARNING] ", BuildActivity.Context.NONE, "");
        console.accept("[WARNING] ike-build-report: 0 failure(s), 1 attention item(s)",
                BuildActivity.Context.NONE, "");
        console.accept("\tat some.Stack.frame(Stack.java:1)", BuildActivity.Context.NONE, "");

        assertThat(console.snapshot()).isEmpty();
    }

    @Test
    void theExecutionRootIsRemovedFromMessages() {
        console.accept("[WARNING] Overwriting /work/ws/alpha/target/classes/A.class",
                BuildActivity.Context.NONE, "/work/ws");

        assertThat(console.snapshot()).extracting(ConsoleMessages.Item::message)
                .containsExactly("Overwriting alpha/target/classes/A.class");
    }

    @Test
    void theSameCompilerWarningInManyFilesFoldsUnderItsLintCategory() {
        BuildActivity.Context compile = new BuildActivity.Context("kview", "maven-compiler-plugin:compile");
        for (String file : List.of("A", "B", "C")) {
            console.accept("[WARNING] /ws/kview/src/" + file
                    + ".java:[10,54] [deprecation] KlFieldFactory in pkg has been deprecated", compile, "/ws");
        }
        console.accept("[WARNING] /ws/kview/src/A.java:[3,1] no category here", compile, "/ws");
        console.accept("[ERROR] /ws/kview/src/A.java:[3,1] cannot find symbol", compile, "/ws");

        assertThat(console.snapshot()).containsExactly(
                new ConsoleMessages.Item(ConsoleMessages.Level.ERROR,
                        "kview/src/A.java:[3,1] cannot find symbol", 1,
                        java.util.Map.of("kview", 1), List.of("maven-compiler-plugin:compile")),
                new ConsoleMessages.Item(ConsoleMessages.Level.WARNING,
                        "KlFieldFactory in pkg has been deprecated", 3,
                        java.util.Map.of("kview", 3), List.of("maven-compiler-plugin:compile"),
                        "compiler: deprecation", 3),
                new ConsoleMessages.Item(ConsoleMessages.Level.WARNING,
                        "no category here", 1,
                        java.util.Map.of("kview", 1), List.of("maven-compiler-plugin:compile"),
                        "compiler: other", 1));
    }

    @Test
    void uncategorizedCompilerWarningsAreFiledByTheirWording() {
        BuildActivity.Context compile = new BuildActivity.Context("entity", "maven-compiler-plugin:compile");
        console.accept("[WARNING] /ws/A.java:[1,1] getFast(int) in Entity has been deprecated"
                + " and marked for removal", compile, "/ws");
        console.accept("[WARNING] /ws/A.java:[2,1] zip() in RichIterable has been deprecated", compile, "/ws");

        assertThat(console.snapshot()).extracting(ConsoleMessages.Item::kind)
                .containsExactly("compiler: removal", "compiler: deprecation");
    }

    @Test
    void skippedTestLinesFoldByClassAndTheModuleTotalIsDropped() {
        BuildActivity.Context test = new BuildActivity.Context("elk-owlapi-test", "maven-surefire-plugin:test");
        console.accept("[WARNING] Tests run: 68, Failures: 0, Errors: 0, Skipped: 4,"
                + " Time elapsed: 2.604 s -- in org.example.QueryTest", test, "");
        console.accept("[WARNING] Tests run: 2238, Failures: 0, Errors: 0, Skipped: 42", test, "");

        assertThat(console.snapshot()).extracting(ConsoleMessages.Item::kind, ConsoleMessages.Item::message)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                        "tests: skipped", "org.example.QueryTest — 4 of 68 skipped"));
    }

    @Test
    void dependencyLinesAreFiledUnderTheAnalyzersHeading() {
        BuildActivity.Context analyze =
                new BuildActivity.Context("framework", "maven-dependency-plugin:analyze-only");
        console.accept("[WARNING] Unused declared dependencies found:", analyze, "");
        console.accept("[WARNING]    org.junit.jupiter:junit-jupiter-api:jar:6.1.3:test", analyze, "");
        console.accept("[WARNING] Used undeclared dependencies found:", analyze, "");
        console.accept("[WARNING]    dev.ikm.tinkar:component:jar:1.127.7:compile", analyze, "");

        assertThat(console.snapshot()).extracting(ConsoleMessages.Item::kind, ConsoleMessages.Item::message)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "dependency analysis: Unused declared dependencies",
                                "org.junit.jupiter:junit-jupiter-api:jar:6.1.3:test"),
                        org.assertj.core.groups.Tuple.tuple(
                                "dependency analysis: Used undeclared dependencies",
                                "dev.ikm.tinkar:component:jar:1.127.7:compile"));
    }

    @Test
    void errorsSortBeforeWarningsThenByFrequency() {
        console.accept("[WARNING] once", BuildActivity.Context.NONE, "");
        console.accept("[WARNING] twice", BuildActivity.Context.NONE, "");
        console.accept("[WARNING] twice", BuildActivity.Context.NONE, "");
        console.accept("[ERROR] broken", BuildActivity.Context.NONE, "");

        assertThat(console.snapshot()).extracting(ConsoleMessages.Item::message)
                .containsExactly("broken", "twice", "once");
    }
}
