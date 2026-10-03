package network.ike.tooling.buildreport;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Consolidates the warnings and errors Maven printed to the console:
 * one entry per distinct message, with how often it appeared and which
 * modules and goals produced it.
 *
 * <p>A reactor build repeats the same warning once per module — a JVM
 * notice on stderr, a deprecated plugin parameter — and the repetition
 * buries the few warnings that are different. Folding them is the whole
 * point of this collector.</p>
 *
 * <p>These messages are deliberately <em>not</em> {@link Finding}s.
 * Findings carry stable keys derived from structured events and are
 * what the ledger gates on; console text is prose that changes with
 * plugin versions, so it is reported and never gated.</p>
 */
public final class ConsoleMessages {

    /** The console levels the receipt consolidates. */
    public enum Level {
        /** A line Maven printed as {@code [ERROR]}. */
        ERROR,
        /** A line Maven printed as {@code [WARNING]}. */
        WARNING
    }

    /**
     * One distinct console message.
     *
     * @param level   the level the message was printed at
     * @param message the message, without its level prefix
     * @param count   how many times it was printed
     * @param modules how many times each module printed it, in first-seen
     *                order; lines that could not be tied to a module are
     *                counted in {@code count} only
     * @param goals   the goals it was printed by, in first-seen order
     * @param kind    the family the message was folded into — for example
     *                {@code compiler: deprecation} — or empty for a
     *                message that stands alone
     * @param files   how many source files a folded compiler message was
     *                reported against; zero otherwise
     */
    public record Item(
            Level level,
            String message,
            int count,
            Map<String, Integer> modules,
            List<String> goals,
            String kind,
            int files) {

        /**
         * Creates a message that stands alone, outside any kind.
         *
         * @param level   the level the message was printed at
         * @param message the message, without its level prefix
         * @param count   how many times it was printed
         * @param modules how many times each module printed it
         * @param goals   the goals it was printed by
         */
        public Item(Level level, String message, int count, Map<String, Integer> modules, List<String> goals) {
            this(level, message, count, modules, goals, "", 0);
        }
    }

    /*
     * Two plugins print one line per source location or per dependency,
     * which would otherwise make every line its own "distinct message":
     *
     *   path/Foo.java:[10,54] [deprecation] Bar in pkg has been deprecated
     *   Unused declared dependencies found:
     *      group:artifact:jar:1.0:compile
     *
     * A compiler warning is folded by dropping its location, so the same
     * complaint about thirty files is one message "in 30 files", filed
     * under its lint category. A dependency line is filed under the
     * heading the analyzer printed above it.
     */
    private static final Pattern COMPILER_LINE =
            Pattern.compile("^(\\S.*\\.java):\\[\\d+,\\d+] (?:\\[([\\w-]+)] )?(.+)$");
    private static final Pattern DEPENDENCY_HEADING = Pattern.compile("^(.+ dependencies) found:$");
    private static final Pattern DEPENDENCY_LINE = Pattern.compile("^[^\\s:]+(:[^\\s:]+){3,}$");
    private static final String DEPENDENCY_GOAL_PREFIX = "maven-dependency-plugin:analyze";

    /*
     * Surefire and failsafe print a test class's result line as a
     * warning when it skipped tests, and each line differs by class and
     * elapsed time:
     *
     *   Tests run: 68, Failures: 0, Errors: 0, Skipped: 4, Time elapsed: 2.6 s -- in pkg.SomeTest
     *   Tests run: 2238, Failures: 0, Errors: 0, Skipped: 42
     *
     * The per-class lines fold into one kind; the second form is the
     * module's total of the same skips, and is dropped.
     */
    private static final Pattern TEST_RESULT_LINE = Pattern.compile(
            "^Tests run: (\\d+), Failures: 0, Errors: 0, Skipped: (\\d+)(?:, Time elapsed: .* -- in (\\S+))?$");
    private static final String TESTS_SKIPPED_KIND = "tests: skipped";

    /** Distinct messages kept before further ones are only counted. */
    private static final int MAX_DISTINCT = 2000;

    /** Longest message kept; javac and javadoc can print very long lines. */
    private static final int MAX_MESSAGE_LENGTH = 300;

    private static final Pattern ANSI = Pattern.compile("\u001B\\[[;\\d]*[ -/]*[@-~]");
    private static final Pattern LEVEL_LINE = Pattern.compile("^\\[(WARNING|ERROR)] ?(.*)$");

    /** This extension's own log lines, which describe the receipt itself. */
    private static final String OWN_PREFIX = "ike-build-report";

    private static final class State {
        private int count;
        private final Map<String, Integer> modules = new LinkedHashMap<>();
        private final Set<String> goals = new LinkedHashSet<>();
        private final Set<String> files = new java.util.HashSet<>();
    }

    private record Key(Level level, String kind, String message) {
    }

    /** The dependency-analysis heading each build thread last printed. */
    private final Map<Thread, String> dependencyHeading = new java.util.concurrent.ConcurrentHashMap<>();

    private final Map<Key, State> messages = new LinkedHashMap<>();
    private int overflow;

    /** Creates an empty collector. */
    public ConsoleMessages() {
    }

    /** Clears all recorded messages. */
    public synchronized void reset() {
        messages.clear();
        dependencyHeading.clear();
        overflow = 0;
    }

    /**
     * Offers one rendered console line; lines that are not warnings or
     * errors are ignored.
     *
     * @param line    the line as Maven rendered it, level prefix and
     *                terminal styling included
     * @param context what the logging thread was building
     * @param root    the execution root, removed from the message so the
     *                same warning reads the same on every machine; empty
     *                when unknown
     */
    public void accept(String line, BuildActivity.Context context, String root) {
        if (line == null || line.isEmpty()) {
            return;
        }
        String plain = ANSI.matcher(line).replaceAll("");
        int end = plain.indexOf('\n');
        Matcher matcher = LEVEL_LINE.matcher(end < 0 ? plain : plain.substring(0, end));
        if (!matcher.matches()) {
            return;
        }
        String message = matcher.group(2).strip();
        if (message.isEmpty() || message.startsWith(OWN_PREFIX)) {
            return;
        }
        if (root != null && !root.isEmpty()) {
            message = message.replace(root + "/", "").replace(root, ".");
        }
        if (message.length() > MAX_MESSAGE_LENGTH) {
            message = message.substring(0, MAX_MESSAGE_LENGTH) + "…";
        }
        Level level = Level.valueOf(matcher.group(1));
        String kind = "";
        String file = "";
        if (level == Level.WARNING) {
            // Errors keep their location: it is what a reader needs to fix them.
            Matcher compiler = COMPILER_LINE.matcher(message);
            if (compiler.matches()) {
                file = compiler.group(1);
                message = compiler.group(3);
                kind = "compiler: " + (compiler.group(2) != null ? compiler.group(2) : lintCategory(message));
            } else if (TEST_RESULT_LINE.matcher(message).matches()) {
                Matcher result = TEST_RESULT_LINE.matcher(message);
                result.matches();
                if (result.group(3) == null) {
                    return;
                }
                kind = TESTS_SKIPPED_KIND;
                message = result.group(3) + " — " + result.group(2) + " of " + result.group(1) + " skipped";
            } else if (context.goal().startsWith(DEPENDENCY_GOAL_PREFIX)) {
                Matcher heading = DEPENDENCY_HEADING.matcher(message);
                if (heading.matches()) {
                    // The heading only names the lines that follow it.
                    dependencyHeading.put(Thread.currentThread(), heading.group(1));
                    return;
                }
                String current = dependencyHeading.get(Thread.currentThread());
                if (current != null && DEPENDENCY_LINE.matcher(message).matches()) {
                    kind = "dependency analysis: " + current;
                }
            }
        }
        record(new Key(level, kind, message), context, file);
    }

    /**
     * Names the lint category of a compiler warning that was printed
     * without one — javac only brackets the category under some
     * {@code -Xlint} configurations, but the wording of the two
     * commonest warnings is fixed.
     */
    private static String lintCategory(String message) {
        if (message.contains("has been deprecated and marked for removal")) {
            return "removal";
        }
        if (message.contains("has been deprecated")) {
            return "deprecation";
        }
        return "other";
    }

    private synchronized void record(Key key, BuildActivity.Context context, String file) {
        State state = messages.get(key);
        if (state == null) {
            if (messages.size() >= MAX_DISTINCT) {
                overflow++;
                return;
            }
            state = new State();
            messages.put(key, state);
        }
        state.count++;
        if (!file.isEmpty()) {
            state.files.add(file);
        }
        if (!context.module().isEmpty()) {
            state.modules.merge(context.module(), 1, Integer::sum);
        }
        if (!context.goal().isEmpty()) {
            state.goals.add(context.goal());
        }
    }

    /**
     * Returns the consolidated messages: errors before warnings, then
     * most frequent first, then in first-seen order.
     *
     * @return one item per distinct message
     */
    public synchronized List<Item> snapshot() {
        List<Item> items = new ArrayList<>();
        for (Map.Entry<Key, State> entry : messages.entrySet()) {
            State state = entry.getValue();
            items.add(new Item(
                    entry.getKey().level(),
                    entry.getKey().message(),
                    state.count,
                    java.util.Collections.unmodifiableMap(new LinkedHashMap<>(state.modules)),
                    List.copyOf(state.goals),
                    entry.getKey().kind(),
                    state.files.size()));
        }
        // The sort is stable, so first-seen order breaks ties.
        items.sort(Comparator.comparing(Item::level)
                .thenComparing(Comparator.comparingInt(Item::count).reversed()));
        return items;
    }

    /**
     * Returns how many lines arrived after the distinct-message limit
     * was reached and so were counted but not kept.
     *
     * @return the number of unrecorded lines
     */
    public synchronized int overflow() {
        return overflow;
    }
}
