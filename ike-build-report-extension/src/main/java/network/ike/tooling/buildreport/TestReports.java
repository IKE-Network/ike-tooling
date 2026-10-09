package network.ike.tooling.buildreport;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Reads the test counts and times from the surefire and failsafe XML
 * reports every module leaves in its build directory.
 *
 * <p>Only the {@code <testsuite>} root element is read — its
 * {@code tests}, {@code failures}, {@code errors}, {@code skipped} and
 * {@code time} attributes — so a report of any size costs one element.
 * The reports are the record the plugins themselves write; nothing is
 * taken from the console.</p>
 */
final class TestReports {

    /** Where surefire writes its reports, under the build directory. */
    static final String SUREFIRE_DIRECTORY = "surefire-reports";

    /** Where failsafe writes its reports, under the build directory. */
    static final String FAILSAFE_DIRECTORY = "failsafe-reports";

    /** How many of the slowest test classes the measures keep. */
    static final int SLOWEST_KEPT = 10;

    private static final String REPORT_PREFIX = "TEST-";
    private static final String REPORT_SUFFIX = ".xml";
    private static final String SUITE_ELEMENT = "testsuite";

    /**
     * One test class's report.
     *
     * @param name     the test class as the report names it
     * @param tests    tests run
     * @param failures assertion failures
     * @param errors   tests that threw
     * @param skipped  tests skipped
     * @param seconds  elapsed time in seconds
     */
    record Suite(String name, int tests, int failures, int errors, int skipped, double seconds) {
    }

    private TestReports() {
    }

    /**
     * Sums the reports of every module into the measures.
     *
     * @param modules   the reactor's modules
     * @param values    the session's measures, added to
     * @param perModule the per-module measures, added to
     * @param slowest   the slowest test classes, added to
     * @param notes     what could not be read, added to
     */
    static void collect(
            List<ModuleLocation> modules,
            Map<String, Double> values,
            Map<String, Map<String, Double>> perModule,
            List<Measures.SlowSuite> slowest,
            List<String> notes) {
        long run = 0;
        long failed = 0;
        long skipped = 0;
        double seconds = 0;
        boolean any = false;
        List<Measures.SlowSuite> suites = new ArrayList<>();
        for (ModuleLocation module : modules) {
            long moduleRun = 0;
            long moduleFailed = 0;
            long moduleSkipped = 0;
            double moduleSeconds = 0;
            boolean moduleAny = false;
            for (String directory : List.of(SUREFIRE_DIRECTORY, FAILSAFE_DIRECTORY)) {
                Path reports = module.buildDirectory().resolve(directory);
                if (!Files.isDirectory(reports)) {
                    continue;
                }
                List<Path> files;
                try {
                    files = listReports(reports);
                } catch (IOException e) {
                    notes.add("tests: could not list " + reports + ": " + e);
                    continue;
                }
                for (Path file : files) {
                    Suite suite;
                    try {
                        suite = read(file);
                    } catch (IOException | XMLStreamException | RuntimeException e) {
                        notes.add("tests: could not read " + file + ": " + e);
                        continue;
                    }
                    if (suite == null) {
                        continue;
                    }
                    moduleAny = true;
                    moduleRun += suite.tests();
                    moduleFailed += suite.failures() + suite.errors();
                    moduleSkipped += suite.skipped();
                    moduleSeconds += suite.seconds();
                    suites.add(new Measures.SlowSuite(module.name(), suite.name(), suite.seconds()));
                }
            }
            if (moduleAny) {
                any = true;
                Map<String, Double> mine = perModule.computeIfAbsent(module.name(), key -> new LinkedHashMap<>());
                mine.put(MeasureKey.TESTS_RUN.key(), (double) moduleRun);
                mine.put(MeasureKey.TESTS_FAILED.key(), (double) moduleFailed);
                mine.put(MeasureKey.TESTS_SKIPPED.key(), (double) moduleSkipped);
                mine.put(MeasureKey.TESTS_SECONDS.key(), round(moduleSeconds));
                run += moduleRun;
                failed += moduleFailed;
                skipped += moduleSkipped;
                seconds += moduleSeconds;
            }
        }
        if (!any) {
            notes.add("tests: no surefire or failsafe reports in this session");
            return;
        }
        values.put(MeasureKey.TESTS_RUN.key(), (double) run);
        values.put(MeasureKey.TESTS_FAILED.key(), (double) failed);
        values.put(MeasureKey.TESTS_SKIPPED.key(), (double) skipped);
        values.put(MeasureKey.TESTS_SECONDS.key(), round(seconds));
        suites.sort(Comparator.comparingDouble(Measures.SlowSuite::seconds).reversed());
        slowest.addAll(suites.subList(0, Math.min(SLOWEST_KEPT, suites.size())));
    }

    /**
     * Lists a report directory's {@code TEST-*.xml} files, sorted by name.
     *
     * @param directory the report directory
     * @return the report files
     * @throws IOException when the directory cannot be listed
     */
    static List<Path> listReports(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.startsWith(REPORT_PREFIX) && name.endsWith(REPORT_SUFFIX);
                    })
                    .sorted()
                    .toList();
        }
    }

    /**
     * Reads one report's root element.
     *
     * @param file the report file
     * @return the suite, or {@code null} when the root element is not a
     *         test suite
     * @throws IOException        when the file cannot be read
     * @throws XMLStreamException when the file is not well-formed XML
     */
    static Suite read(Path file) throws IOException, XMLStreamException {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        try (InputStream in = Files.newInputStream(file)) {
            XMLStreamReader reader = factory.createXMLStreamReader(in);
            try {
                while (reader.hasNext()) {
                    if (reader.next() != XMLStreamConstants.START_ELEMENT) {
                        continue;
                    }
                    if (!SUITE_ELEMENT.equals(reader.getLocalName())) {
                        return null;
                    }
                    return new Suite(
                            attribute(reader, "name", file.getFileName().toString()),
                            intAttribute(reader, "tests"),
                            intAttribute(reader, "failures"),
                            intAttribute(reader, "errors"),
                            intAttribute(reader, "skipped"),
                            doubleAttribute(reader, "time"));
                }
            } finally {
                reader.close();
            }
        }
        return null;
    }

    private static String attribute(XMLStreamReader reader, String name, String fallback) {
        String value = reader.getAttributeValue(null, name);
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private static int intAttribute(XMLStreamReader reader, String name) {
        String value = reader.getAttributeValue(null, name);
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double doubleAttribute(XMLStreamReader reader, String name) {
        String value = reader.getAttributeValue(null, name);
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            // Surefire writes the time with a dot whatever the locale,
            // but a report from another tool may not.
            return Double.parseDouble(value.strip().replace(',', '.'));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Rounds seconds to the millisecond, so sums do not carry float noise. */
    private static double round(double seconds) {
        return Math.round(seconds * 1000.0) / 1000.0;
    }

    /**
     * Formats seconds for the receipt.
     *
     * @param seconds the time
     * @return the same rendering the BUILD section uses
     */
    static String formatSeconds(double seconds) {
        return ReceiptRenderer.formatDuration(java.time.Duration.ofMillis(Math.round(seconds * 1000.0)));
    }

    /** Keeps the locale-dependent formatting out of the class's callers. */
    static String percent(double value) {
        return String.format(Locale.ROOT, "%.1f%%", value);
    }
}
