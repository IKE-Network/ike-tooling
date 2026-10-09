package network.ike.tooling.buildreport;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Collects what one session was like, in numbers (ike-issues#1207):
 * warnings by kind, tests, coverage, time, the sizes the ledger names,
 * and the measurements its tests left behind.
 *
 * <p>Every source is something the session already produced — the
 * console the extension listens to, the activity it timed, the XML the
 * test plugins wrote, the execution data the JaCoCo agent dumped, the
 * properties a test saved — read once at session end. Nothing here
 * runs a tool or changes a file.</p>
 */
public final class MeasureCollector {

    /**
     * Where a test leaves measurements, under its module's build
     * directory: every numeric property of every {@code .properties}
     * file there becomes a measure under the key the test wrote.
     */
    public static final String MEASUREMENTS_DIRECTORY = "measurements";

    private static final String MEASUREMENTS_SUFFIX = ".properties";
    private static final String STDERR_PREFIX = "[stderr]";
    private static final String JAVADOC_GOAL_PREFIX = "maven-javadoc-plugin:";
    private static final String COMPILER_KIND_PREFIX = "compiler: ";
    private static final String DEPENDENCY_KIND_PREFIX = "dependency analysis: ";
    private static final String TESTS_SKIPPED_KIND = "tests: skipped";
    private static final String LINT_DEPRECATION = "deprecation";
    private static final String LINT_REMOVAL = "removal";
    private static final String GLOB_METACHARACTERS = "*?[{\\";

    /** The warning kinds, in the order the receipt lists them. */
    private static final List<MeasureKey> WARNING_KINDS = List.of(
            MeasureKey.WARNINGS_COMPILER_DEPRECATION,
            MeasureKey.WARNINGS_COMPILER_REMOVAL,
            MeasureKey.WARNINGS_COMPILER_OTHER,
            MeasureKey.WARNINGS_JAVADOC,
            MeasureKey.WARNINGS_DEPENDENCY,
            MeasureKey.WARNINGS_STDERR,
            MeasureKey.WARNINGS_TESTS,
            MeasureKey.WARNINGS_OTHER);

    private MeasureCollector() {
    }

    /**
     * Collects the session's measures.
     *
     * @param root     the execution root, which size globs are relative to
     * @param modules  the reactor's modules
     * @param overview what the session built and how long it took; null
     *                 when unknown
     * @param console  the console's counted warnings and errors
     * @param ledger   the ledger, for the sizes it names
     * @return the measures, with a note for everything that could not be
     *         measured
     */
    public static Measures collect(
            Path root,
            List<ModuleLocation> modules,
            BuildActivity.Overview overview,
            ReceiptRenderer.Console console,
            Ledger ledger) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(modules, "modules");
        Objects.requireNonNull(console, "console");
        Objects.requireNonNull(ledger, "ledger");
        Map<String, Double> values = new LinkedHashMap<>();
        Map<String, Map<String, Double>> perModule = new LinkedHashMap<>();
        List<Measures.SlowSuite> slowest = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        build(overview, values);
        warnings(console, values, notes);
        TestReports.collect(modules, values, perModule, slowest, notes);
        CoverageMerge.collect(modules, values, perModule, notes);
        sizes(root, ledger.sizes(), values, notes);
        measurements(modules, values, notes);
        return new Measures(values, perModule, slowest, notes);
    }

    /**
     * Returns the warning kinds in the order the receipt lists them.
     *
     * @return the kinds, every one a {@code warnings.*} key
     */
    public static List<MeasureKey> warningKinds() {
        return WARNING_KINDS;
    }

    /**
     * Files one consolidated console warning under its kind.
     *
     * @param item the warning as the console collector folded it
     * @return the kind's measure key
     */
    static MeasureKey warningKind(ConsoleMessages.Item item) {
        String kind = item.kind();
        if (kind.startsWith(COMPILER_KIND_PREFIX)) {
            String lint = kind.substring(COMPILER_KIND_PREFIX.length());
            return switch (lint) {
                case LINT_DEPRECATION -> MeasureKey.WARNINGS_COMPILER_DEPRECATION;
                case LINT_REMOVAL -> MeasureKey.WARNINGS_COMPILER_REMOVAL;
                default -> MeasureKey.WARNINGS_COMPILER_OTHER;
            };
        }
        if (kind.startsWith(DEPENDENCY_KIND_PREFIX)) {
            return MeasureKey.WARNINGS_DEPENDENCY;
        }
        if (kind.equals(TESTS_SKIPPED_KIND)) {
            return MeasureKey.WARNINGS_TESTS;
        }
        if (item.message().startsWith(STDERR_PREFIX)) {
            return MeasureKey.WARNINGS_STDERR;
        }
        for (String goal : item.goals()) {
            if (goal.startsWith(JAVADOC_GOAL_PREFIX)) {
                return MeasureKey.WARNINGS_JAVADOC;
            }
        }
        return MeasureKey.WARNINGS_OTHER;
    }

    private static void build(BuildActivity.Overview overview, Map<String, Double> values) {
        if (overview == null) {
            return;
        }
        values.put(MeasureKey.BUILD_SECONDS.key(), Math.round(overview.wallTime().toMillis() / 100.0) / 10.0);
        values.put(MeasureKey.BUILD_MODULES_BUILT.key(),
                (double) overview.count(BuildActivity.ModuleResult.BUILT));
        values.put(MeasureKey.BUILD_MODULES_FAILED.key(),
                (double) overview.count(BuildActivity.ModuleResult.FAILED));
        values.put(MeasureKey.BUILD_THREADS.key(), (double) overview.threads());
    }

    private static void warnings(ReceiptRenderer.Console console, Map<String, Double> values, List<String> notes) {
        if (!console.captured()) {
            notes.add("warnings: the console was not captured");
            return;
        }
        Map<MeasureKey, Long> byKind = new EnumMap<>(MeasureKey.class);
        for (MeasureKey kind : WARNING_KINDS) {
            byKind.put(kind, 0L);
        }
        long total = 0;
        long errors = 0;
        for (ConsoleMessages.Item item : console.items()) {
            if (item.level() == ConsoleMessages.Level.ERROR) {
                errors += item.count();
                continue;
            }
            total += item.count();
            byKind.merge(warningKind(item), (long) item.count(), Long::sum);
        }
        values.put(MeasureKey.WARNINGS_TOTAL.key(), (double) total);
        for (MeasureKey kind : WARNING_KINDS) {
            values.put(kind.key(), (double) byKind.get(kind));
        }
        values.put(MeasureKey.ERRORS_CONSOLE.key(), (double) errors);
    }

    /**
     * Sizes the files and trees the ledger names.
     *
     * @param root    the execution root the globs are relative to
     * @param entries the ledger's size entries
     * @param values  the session's measures, added to
     * @param notes   what matched nothing, added to
     */
    static void sizes(Path root, List<SizeEntry> entries, Map<String, Double> values, List<String> notes) {
        for (SizeEntry entry : entries) {
            try {
                long size = sizeOf(root, entry.path());
                if (size < 0) {
                    notes.add("size: " + entry.key() + " — nothing matches " + entry.path());
                    continue;
                }
                values.put(entry.key(), (double) size);
            } catch (IOException | RuntimeException e) {
                notes.add("size: " + entry.key() + " — could not measure " + entry.path() + ": " + e);
            }
        }
    }

    /**
     * Sums the size of everything a glob matches under the root.
     *
     * <p>The walk starts at the glob's longest fixed prefix, so a
     * pattern that begins with a directory name costs that directory,
     * not the whole tree. A matching directory counts as its whole tree
     * and is not descended further.</p>
     *
     * @param root the execution root
     * @param glob the pattern, relative to the root
     * @return the total size in bytes, or {@code -1} when nothing matched
     * @throws IOException when the tree cannot be walked
     */
    static long sizeOf(Path root, String glob) throws IOException {
        PathMatcher matcher = root.getFileSystem().getPathMatcher("glob:" + glob);
        Path start = fixedStart(root, glob);
        if (!Files.exists(start)) {
            return -1;
        }
        if (matcher.matches(root.relativize(start))) {
            return treeSize(start);
        }
        if (!Files.isDirectory(start)) {
            return -1;
        }
        long[] total = {0L};
        boolean[] matched = {false};
        Files.walkFileTree(start, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                if (!directory.equals(start) && matcher.matches(root.relativize(directory))) {
                    total[0] += treeSize(directory);
                    matched[0] = true;
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (matcher.matches(root.relativize(file))) {
                    total[0] += attributes.size();
                    matched[0] = true;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) {
                return FileVisitResult.CONTINUE;
            }
        });
        return matched[0] ? total[0] : -1;
    }

    /** The glob's leading segments without metacharacters, resolved against the root. */
    private static Path fixedStart(Path root, String glob) {
        Path start = root;
        for (String segment : glob.split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            boolean fixed = true;
            for (int index = 0; index < segment.length() && fixed; index++) {
                fixed = GLOB_METACHARACTERS.indexOf(segment.charAt(index)) < 0;
            }
            if (!fixed) {
                break;
            }
            start = start.resolve(segment);
        }
        return start;
    }

    private static long treeSize(Path path) throws IOException {
        if (Files.isRegularFile(path)) {
            return Files.size(path);
        }
        try (Stream<Path> files = Files.walk(path)) {
            return files.filter(Files::isRegularFile).mapToLong(file -> {
                try {
                    return Files.size(file);
                } catch (IOException e) {
                    return 0L;
                }
            }).sum();
        }
    }

    /**
     * Reads the measurements every module's tests left.
     *
     * @param modules the reactor's modules
     * @param values  the session's measures, added to
     * @param notes   what was not used, added to
     */
    static void measurements(List<ModuleLocation> modules, Map<String, Double> values, List<String> notes) {
        for (ModuleLocation module : modules) {
            Path directory = module.buildDirectory().resolve(MEASUREMENTS_DIRECTORY);
            if (!Files.isDirectory(directory)) {
                continue;
            }
            List<Path> files;
            try (Stream<Path> entries = Files.list(directory)) {
                files = entries
                        .filter(path -> path.getFileName().toString().endsWith(MEASUREMENTS_SUFFIX))
                        .sorted()
                        .toList();
            } catch (IOException e) {
                notes.add("measurements: could not list " + directory + ": " + e);
                continue;
            }
            for (Path file : files) {
                Properties properties = new Properties();
                try (InputStream in = Files.newInputStream(file)) {
                    properties.load(in);
                } catch (IOException | IllegalArgumentException e) {
                    notes.add("measurements: could not read " + file + ": " + e);
                    continue;
                }
                for (String key : new TreeSet<>(properties.stringPropertyNames())) {
                    double value;
                    try {
                        value = Double.parseDouble(properties.getProperty(key).strip());
                    } catch (NumberFormatException e) {
                        continue;
                    }
                    if (Double.isNaN(value) || Double.isInfinite(value)) {
                        continue;
                    }
                    if (values.containsKey(key)) {
                        notes.add("measurements: " + key + " in " + module.name() + "/" + file.getFileName()
                                + " repeats an earlier value and was not used");
                        continue;
                    }
                    values.put(key, value);
                }
            }
        }
    }
}
