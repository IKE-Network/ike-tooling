package network.ike.tooling.buildreport;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.ExecutionDataWriter;
import org.jacoco.core.data.SessionInfoStore;
import org.jacoco.core.instr.Instrumenter;
import org.jacoco.core.runtime.IRuntime;
import org.jacoco.core.runtime.LoggerRuntime;
import org.jacoco.core.runtime.RuntimeData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The coverage merge, on execution data produced the way the JaCoCo
 * agent produces it: a class instrumented and run in its own loader.
 */
class CoverageMergeTest {

    @TempDir
    Path tempDir;

    /** The class whose coverage is measured; only one of its two branches ever runs. */
    public static final class Target {

        private Target() {
        }

        /**
         * Picks a number.
         *
         * @param flag which number
         * @return one or two
         */
        public static int pick(boolean flag) {
            if (flag) {
                return 1;
            }
            return 2;
        }
    }

    @Test
    void mergesExecutionDataAcrossModulesAndAnalyzesTheModuleThatOwnsTheClass() throws Exception {
        String name = Target.class.getName();
        byte[] original;
        try (var in = Target.class.getResourceAsStream("/" + name.replace('.', '/') + ".class")) {
            original = in.readAllBytes();
        }
        // Module alpha owns the class; module beta ran the test that executed it.
        Path alpha = tempDir.resolve("alpha");
        Path beta = tempDir.resolve("beta");
        Path classFile = alpha.resolve("target/classes").resolve(name.replace('.', '/') + ".class");
        Files.createDirectories(classFile.getParent());
        Files.write(classFile, original);
        Files.createDirectories(beta.resolve("target"));

        IRuntime runtime = new LoggerRuntime();
        byte[] instrumented = new Instrumenter(runtime).instrument(original, name);
        RuntimeData data = new RuntimeData();
        runtime.startup(data);
        Class<?> loaded = new MemoryClassLoader(name, instrumented).loadClass(name);
        loaded.getMethod("pick", boolean.class).invoke(null, true);
        ExecutionDataStore store = new ExecutionDataStore();
        SessionInfoStore sessions = new SessionInfoStore();
        data.collect(store, sessions, false);
        runtime.shutdown();
        try (OutputStream out = Files.newOutputStream(beta.resolve("target").resolve(CoverageMerge.INTEGRATION_DATA))) {
            ExecutionDataWriter writer = new ExecutionDataWriter(out);
            sessions.accept(writer);
            store.accept(writer);
        }

        List<ModuleLocation> modules = List.of(
                ModuleLocation.standard("alpha", alpha), ModuleLocation.standard("beta", beta));
        Map<String, Double> values = new LinkedHashMap<>();
        Map<String, Map<String, Double>> perModule = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();

        CoverageMerge.collect(modules, values, perModule, notes);

        assertThat(notes).isEmpty();
        assertThat(values.get(MeasureKey.COVERAGE_MODULES.key())).isEqualTo(1.0);
        double total = values.get(MeasureKey.COVERAGE_LINE_TOTAL.key());
        double covered = values.get(MeasureKey.COVERAGE_LINE_COVERED.key());
        assertThat(total).isGreaterThan(0);
        assertThat(covered).isGreaterThan(0).isLessThan(total);
        assertThat(values.get(MeasureKey.COVERAGE_BRANCH_TOTAL.key())).isEqualTo(2.0);
        assertThat(values.get(MeasureKey.COVERAGE_BRANCH_COVERED.key())).isEqualTo(1.0);
        assertThat(values.get(MeasureKey.COVERAGE_BRANCH_PERCENT.key())).isEqualTo(50.0);
        assertThat(values.get(MeasureKey.COVERAGE_LINE_PERCENT.key()))
                .isEqualTo(CoverageMerge.percent((long) covered, (long) total));
        assertThat(perModule).containsOnlyKeys("alpha");
        assertThat(perModule.get("alpha").get(MeasureKey.COVERAGE_LINE_COVERED.key())).isEqualTo(covered);
    }

    @Test
    void withoutExecutionDataCoverageIsNotMeasured() throws Exception {
        Path alpha = tempDir.resolve("alpha");
        Files.createDirectories(alpha.resolve("target/classes"));
        Map<String, Double> values = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();

        CoverageMerge.collect(List.of(ModuleLocation.standard("alpha", alpha)), values, new LinkedHashMap<>(), notes);

        assertThat(values).isEmpty();
        assertThat(notes).singleElement().asString().contains("no JaCoCo execution data");
    }

    @Test
    void percentIsRoundedToTwoDecimalsAndZeroWhenNothingToCover() {
        assertThat(CoverageMerge.percent(1, 3)).isEqualTo(33.33);
        assertThat(CoverageMerge.percent(2, 3)).isEqualTo(66.67);
        assertThat(CoverageMerge.percent(0, 0)).isEqualTo(0.0);
    }

    /** Defines one instrumented class under its original name, ahead of the parent loader. */
    private static final class MemoryClassLoader extends ClassLoader {

        private final String name;
        private final byte[] bytes;

        MemoryClassLoader(String name, byte[] bytes) {
            super(CoverageMergeTest.class.getClassLoader());
            this.name = name;
            this.bytes = bytes;
        }

        @Override
        protected Class<?> loadClass(String wanted, boolean resolve) throws ClassNotFoundException {
            if (wanted.equals(name)) {
                Class<?> defined = findLoadedClass(wanted);
                return defined != null ? defined : defineClass(wanted, bytes, 0, bytes.length);
            }
            return super.loadClass(wanted, resolve);
        }
    }
}
