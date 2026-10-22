package network.ike.tooling.buildreport;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Hands the session's measures to TeamCity as build statistics
 * (ike-issues#1207).
 *
 * <p>TeamCity reads {@code ##teamcity[buildStatisticValue …]} service
 * messages from a build's output and keeps each key's value with the
 * build, which is what its charts, its "fail build on metric change"
 * conditions and its REST API then read. Coverage is also reported
 * under the keys TeamCity reserves for it, so the server's own coverage
 * views and conditions see the same numbers. The extension prints only
 * when {@value #ENVIRONMENT_VARIABLE} is set, which every TeamCity build
 * step has; a local build prints nothing.</p>
 */
public final class TeamCityStatistics {

    /** The environment variable every TeamCity build step carries. */
    public static final String ENVIRONMENT_VARIABLE = "TEAMCITY_VERSION";

    /**
     * TeamCity's own coverage keys, reported beside the extension's so
     * the server computes its percentages and shows coverage on the
     * build overview.
     */
    private static final Map<MeasureKey, String> PREDEFINED = Map.of(
            MeasureKey.COVERAGE_LINE_COVERED, "CodeCoverageAbsLCovered",
            MeasureKey.COVERAGE_LINE_TOTAL, "CodeCoverageAbsLTotal",
            MeasureKey.COVERAGE_BRANCH_COVERED, "CodeCoverageAbsRCovered",
            MeasureKey.COVERAGE_BRANCH_TOTAL, "CodeCoverageAbsRTotal");

    private TeamCityStatistics() {
    }

    /**
     * Says whether this build runs under TeamCity.
     *
     * @return {@code true} when the TeamCity environment variable is set
     */
    public static boolean active() {
        String version = System.getenv(ENVIRONMENT_VARIABLE);
        return version != null && !version.isBlank();
    }

    /**
     * Opens the process's own standard output, which TeamCity reads.
     *
     * <p>Not {@code System.out}: Maven 4 replaces that stream and
     * relays whatever a plugin or extension writes to it through its
     * logger as {@code [INFO] [stdout] …}, and a service message that
     * does not start its line is not one to TeamCity. Writing to the
     * file descriptor itself goes past the relay. The stream is never
     * closed, since closing it would close the process's output.</p>
     *
     * @return a line-flushing stream on file descriptor 1
     */
    public static PrintStream processOutput() {
        return new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
    }

    /**
     * Renders the service messages for a session's measures, one per
     * statistic.
     *
     * @param measures the session's measures
     * @return the messages in measure order; a coverage measure with a
     *         TeamCity key of its own yields two
     */
    public static List<String> messages(Measures measures) {
        Objects.requireNonNull(measures, "measures");
        List<String> messages = new ArrayList<>();
        for (Map.Entry<String, Double> entry : measures.values().entrySet()) {
            messages.add(message(key(entry.getKey()), entry.getValue()));
            MeasureKey known = MeasureKey.of(entry.getKey());
            String predefined = known == null ? null : PREDEFINED.get(known);
            if (predefined != null) {
                messages.add(message(predefined, entry.getValue()));
            }
        }
        return messages;
    }

    /**
     * Prints the service messages for a session's measures.
     *
     * @param measures the session's measures
     * @param out      where TeamCity listens — the build's standard output
     * @return how many statistics were printed
     */
    public static int publish(Measures measures, PrintStream out) {
        Objects.requireNonNull(out, "out");
        List<String> messages = messages(measures);
        for (String message : messages) {
            out.println(message);
        }
        out.flush();
        return messages.size();
    }

    /**
     * Renders one statistic message.
     *
     * @param key   the statistic key, already sanitized
     * @param value its value
     * @return the service message
     */
    static String message(String key, double value) {
        return "##teamcity[buildStatisticValue key='" + escape(key) + "' value='" + Numbers.plain(value) + "']";
    }

    /**
     * Keeps a key to the characters TeamCity charts and conditions take
     * as given, replacing anything else with an underscore.
     *
     * @param key the measure key
     * @return the key with only letters, digits, dots, underscores and dashes
     */
    static String key(String key) {
        StringBuilder out = new StringBuilder(key.length());
        for (int index = 0; index < key.length(); index++) {
            char c = key.charAt(index);
            boolean kept = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == '-';
            out.append(kept ? c : '_');
        }
        return out.toString();
    }

    /**
     * Escapes text for a service message attribute.
     *
     * @param text the raw text
     * @return the text with TeamCity's escapes applied
     */
    static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (int index = 0; index < text.length(); index++) {
            char c = text.charAt(index);
            switch (c) {
                case '|' -> out.append("||");
                case '\'' -> out.append("|'");
                case '\n' -> out.append("|n");
                case '\r' -> out.append("|r");
                case '[' -> out.append("|[");
                case ']' -> out.append("|]");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
