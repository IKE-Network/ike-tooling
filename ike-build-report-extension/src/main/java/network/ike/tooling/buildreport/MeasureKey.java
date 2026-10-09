package network.ike.tooling.buildreport;

import java.util.Objects;

/**
 * The measures the extension derives from a session itself, by their
 * stable keys (ike-issues#1207).
 *
 * <p>A key names one quantity for the life of the project: the ledger
 * bounds it, the sidecar records it, and TeamCity charts it under that
 * name, so a key is never renamed once published. Keys a build adds
 * from outside — a size the ledger names, a measurement a test leaves
 * in {@code target/measurements/} — are not enumerated here; they are
 * as the ledger or the test wrote them.</p>
 */
public enum MeasureKey {

    /** Counted warning lines on the console, every kind together. */
    WARNINGS_TOTAL("warnings.total"),

    /** Compiler warnings about deprecated API. */
    WARNINGS_COMPILER_DEPRECATION("warnings.compiler.deprecation"),

    /** Compiler warnings about API deprecated for removal. */
    WARNINGS_COMPILER_REMOVAL("warnings.compiler.removal"),

    /** Every other compiler lint warning. */
    WARNINGS_COMPILER_OTHER("warnings.compiler.other"),

    /** Warnings the javadoc goal printed. */
    WARNINGS_JAVADOC("warnings.javadoc"),

    /** Lines of the dependency analysis: unused declared, used undeclared. */
    WARNINGS_DEPENDENCY("warnings.dependency"),

    /** A tool's stderr, relayed by Maven as a warning. */
    WARNINGS_STDERR("warnings.stderr"),

    /** Test classes reported with skipped tests. */
    WARNINGS_TESTS("warnings.tests"),

    /** Warning lines of no recognized kind. */
    WARNINGS_OTHER("warnings.other"),

    /** Counted error lines on the console. */
    ERRORS_CONSOLE("errors.console"),

    /** Tests run, from the surefire and failsafe reports. */
    TESTS_RUN("tests.run"),

    /** Tests that failed or errored. */
    TESTS_FAILED("tests.failed"),

    /** Tests skipped. */
    TESTS_SKIPPED("tests.skipped"),

    /** Time in tests, in seconds, summed across every test class. */
    TESTS_SECONDS("tests.seconds"),

    /** Source lines executed at least once. */
    COVERAGE_LINE_COVERED("coverage.line.covered"),

    /** Source lines with executable code. */
    COVERAGE_LINE_TOTAL("coverage.line.total"),

    /** Covered lines as a percentage of the total. */
    COVERAGE_LINE_PERCENT("coverage.line.percent"),

    /** Branches executed at least once. */
    COVERAGE_BRANCH_COVERED("coverage.branch.covered"),

    /** Branches in the code. */
    COVERAGE_BRANCH_TOTAL("coverage.branch.total"),

    /** Covered branches as a percentage of the total. */
    COVERAGE_BRANCH_PERCENT("coverage.branch.percent"),

    /** Modules that left JaCoCo execution data this session. */
    COVERAGE_MODULES("coverage.modules"),

    /** Wall-clock time of the session, in seconds. */
    BUILD_SECONDS("build.seconds"),

    /** Modules that built. */
    BUILD_MODULES_BUILT("build.modules.built"),

    /** Modules that failed. */
    BUILD_MODULES_FAILED("build.modules.failed"),

    /** The degree of concurrency the session ran with. */
    BUILD_THREADS("build.threads");

    private final String key;

    MeasureKey(String key) {
        this.key = key;
    }

    /**
     * Returns the key as it appears in the sidecar, the ledger and the
     * TeamCity statistics.
     *
     * @return the stable key text
     */
    public String key() {
        return key;
    }

    /**
     * Finds the enumerated measure for a key.
     *
     * @param key the key text
     * @return the measure, or {@code null} when the key is not one of
     *         the enumerated measures
     */
    public static MeasureKey of(String key) {
        Objects.requireNonNull(key, "key");
        for (MeasureKey candidate : values()) {
            if (candidate.key.equals(key)) {
                return candidate;
            }
        }
        return null;
    }
}
