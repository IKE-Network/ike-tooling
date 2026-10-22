package network.ike.tooling.buildreport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IBundleCoverage;
import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.tools.ExecFileLoader;

/**
 * Merges the JaCoCo execution data every module left and analyzes the
 * reactor's classes against the whole of it (ike-issues#1206, #1207).
 *
 * <p>A module's own JaCoCo report counts only what its own tests
 * executed. Much of the coverage in a working set is cross-module —
 * tinkar-core's integration tests exercise entity, common and the
 * providers — and only shows when every module's classes are analyzed
 * against every module's execution data. Doing that here, with the
 * JaCoCo core API over the files the agent already wrote, gives one
 * correct number per module and per session without an aggregate
 * module to maintain in every reactor.</p>
 */
final class CoverageMerge {

    /** The execution data the surefire run leaves, under the build directory. */
    static final String UNIT_DATA = "jacoco.exec";

    /** The execution data the failsafe run leaves, under the build directory. */
    static final String INTEGRATION_DATA = "jacoco-it.exec";

    private CoverageMerge() {
    }

    /**
     * Adds the coverage measures, when there is execution data to read.
     *
     * <p>Never throws: a JaCoCo that cannot read a class file, or is
     * missing from the realm, becomes a note and the other measures
     * stand.</p>
     *
     * @param modules   the reactor's modules
     * @param values    the session's measures, added to
     * @param perModule the per-module measures, added to
     * @param notes     what could not be measured, added to
     */
    static void collect(
            List<ModuleLocation> modules,
            Map<String, Double> values,
            Map<String, Map<String, Double>> perModule,
            List<String> notes) {
        try {
            merge(modules, values, perModule, notes);
        } catch (IOException | RuntimeException | LinkageError e) {
            notes.add("coverage: not merged — " + e);
        }
    }

    private static void merge(
            List<ModuleLocation> modules,
            Map<String, Double> values,
            Map<String, Map<String, Double>> perModule,
            List<String> notes) throws IOException {
        ExecFileLoader loader = new ExecFileLoader();
        int withData = 0;
        for (ModuleLocation module : modules) {
            boolean loaded = false;
            for (String name : List.of(UNIT_DATA, INTEGRATION_DATA)) {
                Path file = module.buildDirectory().resolve(name);
                if (Files.isRegularFile(file)) {
                    loader.load(file.toFile());
                    loaded = true;
                }
            }
            if (loaded) {
                withData++;
            }
        }
        if (withData == 0) {
            notes.add("coverage: no JaCoCo execution data in this session");
            return;
        }
        long lineCovered = 0;
        long lineTotal = 0;
        long branchCovered = 0;
        long branchTotal = 0;
        for (ModuleLocation module : modules) {
            Path classes = module.outputDirectory();
            if (!Files.isDirectory(classes)) {
                continue;
            }
            CoverageBuilder builder = new CoverageBuilder();
            Analyzer analyzer = new Analyzer(loader.getExecutionDataStore(), builder);
            analyzer.analyzeAll(classes.toFile());
            IBundleCoverage bundle = builder.getBundle(module.name());
            ICounter lines = bundle.getLineCounter();
            if (lines.getTotalCount() == 0) {
                continue;
            }
            ICounter branches = bundle.getBranchCounter();
            Map<String, Double> mine = perModule.computeIfAbsent(module.name(), key -> new LinkedHashMap<>());
            put(mine, lines.getCoveredCount(), lines.getTotalCount(),
                    branches.getCoveredCount(), branches.getTotalCount());
            lineCovered += lines.getCoveredCount();
            lineTotal += lines.getTotalCount();
            branchCovered += branches.getCoveredCount();
            branchTotal += branches.getTotalCount();
        }
        put(values, lineCovered, lineTotal, branchCovered, branchTotal);
        values.put(MeasureKey.COVERAGE_MODULES.key(), (double) withData);
    }

    private static void put(
            Map<String, Double> into, long lineCovered, long lineTotal, long branchCovered, long branchTotal) {
        into.put(MeasureKey.COVERAGE_LINE_COVERED.key(), (double) lineCovered);
        into.put(MeasureKey.COVERAGE_LINE_TOTAL.key(), (double) lineTotal);
        into.put(MeasureKey.COVERAGE_LINE_PERCENT.key(), percent(lineCovered, lineTotal));
        into.put(MeasureKey.COVERAGE_BRANCH_COVERED.key(), (double) branchCovered);
        into.put(MeasureKey.COVERAGE_BRANCH_TOTAL.key(), (double) branchTotal);
        into.put(MeasureKey.COVERAGE_BRANCH_PERCENT.key(), percent(branchCovered, branchTotal));
    }

    /**
     * Computes a percentage to two decimals.
     *
     * @param covered the covered count
     * @param total   the total count
     * @return the percentage; zero when there is nothing to cover
     */
    static double percent(long covered, long total) {
        if (total == 0) {
            return 0.0;
        }
        return Math.round(covered * 10_000.0 / total) / 100.0;
    }
}
