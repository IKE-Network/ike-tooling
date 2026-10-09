package network.ike.tooling.buildreport;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Renders a {@link LedgerEvaluation} as the {@code ike꞉build-report.md}
 * receipt, in the house {@code ws꞉*.md} receipt style: a title line, a
 * tool/version/timestamp line, then only the sections that have content.
 *
 * <p>The receipt reads top-down as "what happened, then what needs
 * you": the work the session did and how long it took, the findings
 * the ledger evaluates, and last the console's warnings folded to one
 * line per distinct message.</p>
 */
public final class ReceiptRenderer {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** How many occurrences of one key the receipt lists before summarizing. */
    private static final int MAX_OCCURRENCES = 5;

    /*
     * Status markers. Markdown has no colour of its own, and inline
     * HTML styling is stripped by most renderers, so the receipt carries
     * colour as emoji: they survive the IDE preview, GitHub, and a plain
     * terminal alike. The palette is the one test harnesses and Maven's
     * console have taught: green passed, red failed, yellow warns, blue
     * informs.
     */
    private static final String GREEN = "🟢";
    private static final String RED = "🔴";
    private static final String YELLOW = "🟡";
    private static final String BLUE = "🔵";
    private static final String NEUTRAL = "⚪";

    /**
     * Marks a module still building in a live receipt. Deliberately not
     * a coloured circle: beside rows of them, one more circle with a
     * time reads as one more finished module.
     */
    private static final String IN_PROGRESS = "⏳";

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** How many plugin goals the timing table lists. */
    private static final int MAX_GOAL_TIMES = 10;

    /** How many distinct console messages the receipt lists before summarizing. */
    private static final int MAX_CONSOLE_ITEMS = 25;

    /** How many messages the receipt lists under each folded kind. */
    private static final int MAX_KIND_ITEMS = 3;

    /** How many module names one console message lists before counting the rest. */
    private static final int MAX_CONSOLE_MODULES = 3;

    /**
     * How one ignore rule fared in a session.
     *
     * @param rule     the rule from the ledger
     * @param lines    how many console lines it ignored
     */
    public record Ignored(ConsoleIgnore rule, int lines) {
    }

    /**
     * The console's warnings and errors, consolidated.
     *
     * @param captured       whether console output could be listened to at all
     * @param items          one item per distinct message that counts, most
     *                       significant first
     * @param overflow       lines counted but not kept once the collector was full
     * @param moduleWarnings counted warning lines keyed by module artifact id
     * @param ignored        every ignore rule with what it ignored, in ledger order
     */
    public record Console(
            boolean captured,
            List<ConsoleMessages.Item> items,
            int overflow,
            Map<String, Integer> moduleWarnings,
            List<Ignored> ignored) {

        /** A console that was listened to and stayed quiet. */
        public static final Console QUIET = new Console(true, List.of(), 0, Map.of(), List.of());

        /**
         * Sorts collected messages into those that count and those the
         * ledger's ignore rules set aside.
         *
         * @param captured whether console output could be listened to at all
         * @param all      every distinct message the session printed
         * @param overflow lines counted but not kept once the collector was full
         * @param rules    the ledger's console ignore rules
         * @return the console as the receipt presents it
         */
        public static Console of(
                boolean captured, List<ConsoleMessages.Item> all, int overflow, List<ConsoleIgnore> rules) {
            int[] ignoredLines = new int[rules.size()];
            List<ConsoleMessages.Item> counted = new java.util.ArrayList<>();
            Map<String, Integer> moduleWarnings = new LinkedHashMap<>();
            for (ConsoleMessages.Item item : all) {
                int rule = 0;
                while (rule < rules.size() && !rules.get(rule).ignores(item)) {
                    rule++;
                }
                if (rule < rules.size()) {
                    ignoredLines[rule] += item.count();
                    continue;
                }
                counted.add(item);
                if (item.level() == ConsoleMessages.Level.WARNING) {
                    item.modules().forEach((module, lines) -> moduleWarnings.merge(module, lines, Integer::sum));
                }
            }
            List<Ignored> ignored = new java.util.ArrayList<>();
            for (int rule = 0; rule < rules.size(); rule++) {
                ignored.add(new Ignored(rules.get(rule), ignoredLines[rule]));
            }
            return new Console(captured, List.copyOf(counted), overflow, moduleWarnings, List.copyOf(ignored));
        }

        private int lines(ConsoleMessages.Level level) {
            return items.stream()
                    .filter(item -> item.level() == level)
                    .mapToInt(ConsoleMessages.Item::count)
                    .sum();
        }

        private int ignoredLines() {
            return ignored.stream().mapToInt(Ignored::lines).sum();
        }
    }

    private ReceiptRenderer() {
    }

    /**
     * Renders a receipt that carries findings only, with no build
     * overview and a quiet console.
     *
     * @param toolVersion the extension version stamped under the title
     * @param timestamp   the session-end time stamped under the title
     * @param mode        the ledger's declared enforcement posture
     * @param ledgerNote  a one-line ledger status; empty when the ledger
     *                    loaded cleanly
     * @param evaluation  the section content
     * @return the receipt as Markdown
     */
    public static String render(
            String toolVersion,
            ZonedDateTime timestamp,
            LedgerMode mode,
            String ledgerNote,
            LedgerEvaluation evaluation) {
        return render(toolVersion, timestamp, mode, ledgerNote, evaluation, null, Console.QUIET);
    }

    /**
     * Renders the receipt.
     *
     * @param toolVersion the extension version stamped under the title
     * @param timestamp   the session-end time stamped under the title
     * @param mode        the ledger's declared enforcement posture
     * @param ledgerNote  a one-line ledger status (for example a parse
     *                    failure); empty when the ledger loaded cleanly
     * @param evaluation  the section content
     * @param overview    what the session built and how long it took;
     *                    null omits the BUILD section
     * @param console     the console's consolidated warnings and errors
     * @return the receipt as Markdown
     */
    public static String render(
            String toolVersion,
            ZonedDateTime timestamp,
            LedgerMode mode,
            String ledgerNote,
            LedgerEvaluation evaluation,
            BuildActivity.Overview overview,
            Console console) {
        return render(toolVersion, timestamp, mode, ledgerNote, evaluation, overview, console, null);
    }

    /**
     * Renders the receipt, measures included.
     *
     * @param toolVersion   the extension version stamped under the title
     * @param timestamp     the session-end time stamped under the title
     * @param mode          the ledger's declared enforcement posture
     * @param ledgerNote    a one-line ledger status (for example a parse
     *                      failure); empty when the ledger loaded cleanly
     * @param evaluation    the section content
     * @param overview      what the session built and how long it took;
     *                      null omits the BUILD section
     * @param console       the console's consolidated warnings and errors
     * @param measureReport the session's measures with the ledger's
     *                      verdict on each bound; null omits the MEASURES
     *                      section
     * @return the receipt as Markdown
     */
    public static String render(
            String toolVersion,
            ZonedDateTime timestamp,
            LedgerMode mode,
            String ledgerNote,
            LedgerEvaluation evaluation,
            BuildActivity.Overview overview,
            Console console,
            MeasureReport measureReport) {
        Objects.requireNonNull(evaluation, "evaluation");
        Objects.requireNonNull(console, "console");
        StringBuilder out = new StringBuilder(1024);
        out.append("# ike:build-report\n");
        out.append('_').append(STAMP.format(timestamp))
                .append(" · ike-build-report-extension ").append(toolVersion)
                .append(" · mode: ").append(mode.name().toLowerCase(java.util.Locale.ROOT))
                .append("_\n\n");
        if (!ledgerNote.isBlank()) {
            for (String line : ledgerNote.strip().split("\n")) {
                out.append("> ").append(noteMarker(line.strip())).append(' ')
                        .append(line.strip()).append('\n');
            }
            out.append('\n');
        }

        renderBuild(out, overview, !evaluation.failures().isEmpty(), console.moduleWarnings(), null);
        renderMeasures(out, measureReport);
        renderFailures(out, evaluation.failures());
        renderAttention(out, evaluation.attention());
        renderAccepted(out, evaluation.accepted());
        renderRatchet(out, evaluation.ratchet());
        renderConsole(out, console);
        renderSummary(out, evaluation, console);
        renderRemediation(out, evaluation);
        return out.toString();
    }

    /**
     * Renders the receipt of a build still in progress: the work so far
     * and the console so far.
     *
     * <p>Findings and the gate verdict are left to the final receipt —
     * comparing a partial build against the ledger's expected counts
     * would report shortfalls that are only a matter of time.</p>
     *
     * @param toolVersion the extension version stamped under the title
     * @param timestamp   the time of this update, by which a reader can
     *                    tell a live receipt from one a killed build left
     * @param overview    what the session has built so far
     * @param console     the console's warnings and errors so far
     * @return the receipt as Markdown
     */
    public static String renderLive(
            String toolVersion, ZonedDateTime timestamp, BuildActivity.Overview overview, Console console) {
        StringBuilder out = new StringBuilder(1024);
        out.append("# ike:build-report\n");
        out.append('_').append(STAMP.format(timestamp))
                .append(" · ike-build-report-extension ").append(toolVersion)
                .append(" · live_\n\n");
        renderBuild(out, overview, false, console.moduleWarnings(), CLOCK.format(timestamp));
        renderConsole(out, console);
        out.append("_Updated as the build runs; findings and the gate verdict are added when it\n");
        out.append("ends. A receipt still RUNNING long after the time above was left by a build\n");
        out.append("that was killed._\n");
        return out.toString();
    }

    /**
     * Renders what the session did: the invocation, the outcome, the
     * wall time, each module with its own time, and the goals the time
     * went to.
     */
    private static void renderBuild(
            StringBuilder out,
            BuildActivity.Overview overview,
            boolean failed,
            Map<String, Integer> moduleWarnings,
            String liveAsOf) {
        boolean live = liveAsOf != null;
        if (overview == null) {
            return;
        }
        long built = overview.count(BuildActivity.ModuleResult.BUILT);
        boolean unsuccessful = failed || overview.count(BuildActivity.ModuleResult.FAILED) > 0;
        out.append("## BUILD\n\n");
        if (live) {
            out.append(IN_PROGRESS).append(" **RUNNING** as of ").append(liveAsOf).append(", ");
        } else {
            out.append(unsuccessful ? RED : GREEN)
                    .append(" **").append(unsuccessful ? "FAILED" : "SUCCESS").append("** in ");
        }
        out.append(formatDuration(overview.wallTime())).append(" — ");
        if (overview.modules().isEmpty()) {
            out.append("no modules were reached");
        } else {
            out.append(built).append(" of ").append(overview.modules().size()).append(" module(s) built");
            appendCount(out, overview, BuildActivity.ModuleResult.BUILDING, live ? "building" : "interrupted");
            appendCount(out, overview, BuildActivity.ModuleResult.FAILED, "failed");
            appendCount(out, overview, BuildActivity.ModuleResult.SKIPPED, "skipped");
            appendCount(out, overview, BuildActivity.ModuleResult.NOT_BUILT, live ? "pending" : "not reached");
        }
        out.append("\n\n");
        if (live) {
            // The modules in flight, pulled out of the list: in a large
            // reactor they are otherwise a few rows among a hundred.
            for (BuildActivity.Module module : overview.modules()) {
                if (module.result() == BuildActivity.ModuleResult.BUILDING) {
                    out.append("- ").append(IN_PROGRESS).append(" now building: **")
                            .append(module.name()).append("**");
                    if (!module.goal().isEmpty()) {
                        out.append(" — ").append(module.goal());
                    }
                    out.append(", ").append(formatDuration(module.time())).append(" so far\n");
                }
            }
        }
        out.append("- invocation: `mvn");
        for (String goal : overview.goals()) {
            out.append(' ').append(goal);
        }
        out.append("`\n");
        if (!overview.profiles().isEmpty()) {
            out.append("- profiles: ").append(String.join(", ", overview.profiles())).append('\n');
        }
        out.append("- environment: ");
        if (!overview.mavenVersion().isBlank()) {
            out.append("Maven ").append(overview.mavenVersion()).append(" · ");
        }
        out.append("JDK ").append(overview.javaVersion()).append(" · ")
                .append(overview.threads()).append(overview.threads() == 1 ? " thread" : " threads")
                .append("\n\n");

        // Fenced text rather than Markdown tables: a table's grid is drawn
        // by each viewer's stylesheet, while fenced text looks the same in
        // the IDE preview, on GitHub, and in a terminal. The fixed-width
        // columns come first and the name last, so no name, however long,
        // can push a column out of line.
        if (!overview.modules().isEmpty()) {
            out.append("```\n");
            for (BuildActivity.Module module : overview.modules()) {
                int warnings = moduleWarnings.getOrDefault(module.name(), 0);
                boolean ran = module.result() == BuildActivity.ModuleResult.BUILT
                        || module.result() == BuildActivity.ModuleResult.BUILDING
                        || module.result() == BuildActivity.ModuleResult.FAILED;
                out.append(marker(module.result(), warnings, live))
                        .append(String.format(java.util.Locale.ROOT, " %8s%s ",
                                ran ? formatDuration(module.time()) : "—",
                                live && module.result() == BuildActivity.ModuleResult.BUILDING ? "…" : " "))
                        .append(module.name())
                        .append(remark(module, warnings, live))
                        .append('\n');
            }
            out.append("```\n\n");
            if (overview.threads() > 1 && overview.modules().size() > 1) {
                out.append("Modules built in parallel, so their times overlap and sum to more than\n");
                out.append("the wall time.\n\n");
            }
        }

        if (!overview.goalTimes().isEmpty()) {
            int shown = Math.min(overview.goalTimes().size(), MAX_GOAL_TIMES);
            out.append("Where the time went, by plugin goal across all modules:\n\n");
            out.append("```\n");
            for (BuildActivity.GoalTime goal : overview.goalTimes().subList(0, shown)) {
                out.append(String.format(java.util.Locale.ROOT, "%8s %5d×  ",
                                formatDuration(goal.time()), goal.executions()))
                        .append(goal.goal()).append('\n');
            }
            if (overview.goalTimes().size() > shown) {
                Duration rest = Duration.ZERO;
                for (BuildActivity.GoalTime goal
                        : overview.goalTimes().subList(shown, overview.goalTimes().size())) {
                    rest = rest.plus(goal.time());
                }
                out.append(String.format(java.util.Locale.ROOT, "%8s %6s  ", formatDuration(rest), ""))
                        .append("… ").append(overview.goalTimes().size() - shown)
                        .append(" further goal(s)\n");
            }
            out.append("```\n\n");
        }
    }

    private static void appendCount(
            StringBuilder out, BuildActivity.Overview overview, BuildActivity.ModuleResult result, String label) {
        long count = overview.count(result);
        if (count > 0) {
            out.append(", ").append(count).append(' ').append(label);
        }
    }

    /**
     * Picks a module's marker. Yellow is kept for one meaning — built,
     * but with warnings on the console — so a skipped module is neutral.
     */
    private static String marker(BuildActivity.ModuleResult result, int warnings, boolean live) {
        return switch (result) {
            case BUILT -> warnings > 0 ? YELLOW : GREEN;
            case BUILDING -> live ? IN_PROGRESS : NEUTRAL;
            case FAILED -> RED;
            case SKIPPED, NOT_BUILT -> NEUTRAL;
        };
    }

    /**
     * Words a module's line only when it is not a plain success: the
     * marker already says "built", but a reader who cannot tell the
     * colours apart, or who is searching the raw text, needs the
     * exceptions spelled out.
     */
    private static String remark(BuildActivity.Module module, int warnings, boolean live) {
        return switch (module.result()) {
            case BUILT -> warnings > 0 ? "  " + warnings + " warning(s)" : "";
            case BUILDING -> !live ? "  interrupted"
                    : module.goal().isEmpty() ? "  STILL BUILDING" : "  STILL BUILDING — " + module.goal();
            case FAILED -> "  FAILED";
            case SKIPPED -> "  skipped";
            case NOT_BUILT -> live ? "  pending" : "  not reached";
        };
    }

    /**
     * Picks the marker for one line of the note under the title: the
     * gate's verdict, or a ledger that could not be used.
     */
    private static String noteMarker(String line) {
        if (line.startsWith("gate: clean")) {
            return GREEN;
        }
        if (line.startsWith("gate: FAILING") || line.startsWith("gate: build already failed")) {
            return RED;
        }
        return YELLOW;
    }

    /**
     * Formats a duration the way a reader says it: {@code 0.4s},
     * {@code 12.3s}, {@code 4m 12s}, {@code 1h 02m}.
     */
    static String formatDuration(Duration duration) {
        long millis = Math.max(0L, duration.toMillis());
        if (millis < 60_000L) {
            return String.format(java.util.Locale.ROOT, "%.1fs", millis / 1000.0);
        }
        long seconds = Math.round(millis / 1000.0);
        if (seconds < 3600L) {
            return String.format(java.util.Locale.ROOT, "%dm %02ds", seconds / 60, seconds % 60);
        }
        return String.format(java.util.Locale.ROOT, "%dh %02dm", seconds / 3600, (seconds % 3600) / 60);
    }

    /**
     * Renders the console's warnings and errors, one line per distinct
     * message with its count and where it came from.
     */
    private static void renderConsole(StringBuilder out, Console console) {
        if (!console.captured()) {
            out.append("## ").append(YELLOW).append(" CONSOLE\n\n");
            out.append("Console warnings were not captured — this Maven does not expose the log\n");
            out.append("sink the extension listens on.\n\n");
            return;
        }
        if (console.items().isEmpty() && console.ignored().isEmpty()) {
            return;
        }
        out.append("## ").append(console.items().isEmpty() ? BLUE : YELLOW).append(" CONSOLE\n\n");
        if (!console.items().isEmpty()) {
            renderConsoleItems(out, console);
        }
        if (!console.ignored().isEmpty()) {
            out.append("Ignored by `").append(ReportSession.LEDGER_RELATIVE_PATH)
                    .append("` — shown here, counted nowhere:\n\n");
            for (Ignored ignored : console.ignored()) {
                out.append("- ").append(BLUE).append(" **").append(ignored.lines()).append("×** `")
                        .append(ignored.rule().match().replace('`', '\'')).append('`');
                if (ignored.lines() == 0) {
                    out.append(" — not seen this session; the rule may no longer be needed");
                } else if (!ignored.rule().reason().isBlank()) {
                    out.append(" — ").append(ignored.rule().reason());
                }
                out.append('\n');
            }
            out.append('\n');
        }
        if (console.lines(ConsoleMessages.Level.WARNING) > 0) {
            out.append("To stop counting a warning, add text it contains to `")
                    .append(ReportSession.LEDGER_RELATIVE_PATH).append("`:\n\n");
            out.append("```yaml\nconsole:\n  ignore:\n    - match: \"text from the warning\"\n");
            out.append("      reason: why it does not matter\n```\n\n");
        }
    }

    private static void renderConsoleItems(StringBuilder out, Console console) {
        int lines = console.items().stream().mapToInt(ConsoleMessages.Item::count).sum();
        out.append(lines).append(" warning and error line(s) folded into ")
                .append(console.items().size())
                .append(" distinct message(s). Reported for the reader; never gated.\n\n");
        List<ConsoleMessages.Item> standalone = new java.util.ArrayList<>();
        Map<String, List<ConsoleMessages.Item>> kinds = new LinkedHashMap<>();
        for (ConsoleMessages.Item item : console.items()) {
            if (item.kind().isEmpty()) {
                standalone.add(item);
            } else {
                kinds.computeIfAbsent(item.kind(), key -> new java.util.ArrayList<>()).add(item);
            }
        }
        int shown = Math.min(standalone.size(), MAX_CONSOLE_ITEMS);
        for (ConsoleMessages.Item item : standalone.subList(0, shown)) {
            boolean error = item.level() == ConsoleMessages.Level.ERROR;
            out.append("- ").append(error ? RED : YELLOW)
                    .append(" **").append(item.count()).append("×** ");
            if (error) {
                out.append("ERROR ");
            }
            out.append('`').append(item.message().replace('`', '\'')).append('`');
            String origin = describeOrigin(item);
            if (!origin.isEmpty()) {
                out.append(" — ").append(origin);
            }
            out.append('\n');
        }
        if (standalone.size() > shown) {
            renderConsoleRemainder(out, standalone.subList(shown, standalone.size()));
        }
        if (console.overflow() > 0) {
            out.append("- … and ").append(console.overflow())
                    .append(" further line(s) not kept — too many distinct messages\n");
        }
        if (!standalone.isEmpty() || console.overflow() > 0) {
            out.append('\n');
        }
        renderConsoleKinds(out, kinds);
    }

    /**
     * Renders the messages that were folded by kind — compiler warnings
     * by lint category, dependency-analysis lines by the analyzer's
     * heading — as one entry per kind with its heaviest messages
     * beneath it.
     */
    private static void renderConsoleKinds(StringBuilder out, Map<String, List<ConsoleMessages.Item>> kinds) {
        if (kinds.isEmpty()) {
            return;
        }
        List<Map.Entry<String, List<ConsoleMessages.Item>>> ordered = new java.util.ArrayList<>(kinds.entrySet());
        ordered.sort(java.util.Comparator.comparingInt(
                (Map.Entry<String, List<ConsoleMessages.Item>> entry) -> lineCount(entry.getValue())).reversed());
        out.append("Folded by kind — every message is in `")
                .append(ReportSession.CONSOLE_RELATIVE_PATH).append("`:\n\n");
        for (Map.Entry<String, List<ConsoleMessages.Item>> entry : ordered) {
            List<ConsoleMessages.Item> items = entry.getValue();
            Map<String, Integer> modules = new LinkedHashMap<>();
            for (ConsoleMessages.Item item : items) {
                item.modules().forEach((module, count) -> modules.merge(module, count, Integer::sum));
            }
            out.append("- ").append(YELLOW).append(" **").append(lineCount(items)).append("×** ")
                    .append(entry.getKey()).append(" — ").append(items.size()).append(" distinct");
            if (!modules.isEmpty()) {
                out.append(" · ").append(describeModuleCounts(modules));
            }
            out.append('\n');
            int shown = Math.min(items.size(), MAX_KIND_ITEMS);
            for (ConsoleMessages.Item item : items.subList(0, shown)) {
                out.append("  - **").append(item.count()).append("×** `")
                        .append(item.message().replace('`', '\'')).append('`');
                if (item.files() > 1) {
                    out.append(" — ").append(item.files()).append(" files");
                } else if (item.files() == 0 && !item.modules().isEmpty()) {
                    out.append(" — ").append(String.join(", ", item.modules().keySet()));
                }
                out.append('\n');
            }
            if (items.size() > shown) {
                out.append("  - … and ").append(items.size() - shown).append(" more\n");
            }
        }
        out.append('\n');
    }

    private static int lineCount(List<ConsoleMessages.Item> items) {
        return items.stream().mapToInt(ConsoleMessages.Item::count).sum();
    }

    /** Names the modules behind a kind, heaviest first, with their line counts. */
    private static String describeModuleCounts(Map<String, Integer> modules) {
        List<Map.Entry<String, Integer>> ordered = new java.util.ArrayList<>(modules.entrySet());
        ordered.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
        StringBuilder text = new StringBuilder();
        int named = Math.min(ordered.size(), MAX_CONSOLE_MODULES);
        for (int index = 0; index < named; index++) {
            text.append(index == 0 ? "" : ", ")
                    .append(ordered.get(index).getKey()).append(' ').append(ordered.get(index).getValue());
        }
        if (ordered.size() > named) {
            text.append(", … +").append(ordered.size() - named).append(" module(s)");
        }
        return text.toString();
    }

    private static String describeOrigin(ConsoleMessages.Item item) {
        StringBuilder origin = new StringBuilder();
        if (!item.goals().isEmpty()) {
            origin.append(String.join(", ", item.goals()));
        }
        List<String> modules = List.copyOf(item.modules().keySet());
        if (!modules.isEmpty()) {
            if (origin.length() > 0) {
                origin.append(" · ");
            }
            int named = Math.min(modules.size(), MAX_CONSOLE_MODULES);
            if (modules.size() > 1) {
                origin.append(modules.size()).append(" modules: ");
            }
            origin.append(String.join(", ", modules.subList(0, named)));
            if (modules.size() > named) {
                origin.append(", … +").append(modules.size() - named);
            }
        }
        return origin.toString();
    }

    /**
     * Renders every counted console message, with no listing limit —
     * the companion file a reader opens when the receipt's top entries
     * are not enough, or when choosing what to ignore.
     *
     * @param console the console's consolidated warnings and errors
     * @return the full listing as Markdown
     */
    public static String renderConsoleListing(Console console) {
        StringBuilder out = new StringBuilder(4096);
        out.append("# ike:build-report — console, in full\n\n");
        out.append("Every distinct warning and error the build printed that is not ignored by `")
                .append(ReportSession.LEDGER_RELATIVE_PATH).append("`.\n\n");
        for (ConsoleMessages.Item item : console.items()) {
            out.append("- **").append(item.count()).append("×** ")
                    .append(item.level() == ConsoleMessages.Level.ERROR ? "ERROR " : "")
                    .append(item.kind().isEmpty() ? "" : "[" + item.kind() + "] ")
                    .append('`').append(item.message().replace('`', '\'')).append('`');
            if (item.files() > 0) {
                out.append(" — ").append(item.files()).append(" file(s)");
            }
            String origin = describeOrigin(item);
            if (!origin.isEmpty()) {
                out.append(" — ").append(origin);
            }
            out.append('\n');
        }
        return out.toString();
    }

    /**
     * Summarizes the messages past the listing limit by the goal that
     * printed them, which is usually what tells a reader whether the
     * tail is worth opening the log for.
     */
    private static void renderConsoleRemainder(StringBuilder out, List<ConsoleMessages.Item> rest) {
        Map<String, Integer> byGoal = new LinkedHashMap<>();
        int lines = 0;
        for (ConsoleMessages.Item item : rest) {
            lines += item.count();
            String goal = item.goals().isEmpty() ? "outside any goal" : item.goals().get(0);
            byGoal.merge(goal, item.count(), Integer::sum);
        }
        out.append("- … and ").append(rest.size()).append(" further distinct message(s), ")
                .append(lines).append(" line(s):");
        String separator = " ";
        for (Map.Entry<String, Integer> entry : byGoal.entrySet()) {
            out.append(separator).append(entry.getKey()).append(' ').append(entry.getValue());
            separator = ", ";
        }
        out.append(" — full list in `").append(ReportSession.CONSOLE_RELATIVE_PATH).append("`\n");
    }

    /** How many lines of sizes and measurements MEASURES lists before counting the rest. */
    private static final int MAX_MEASUREMENT_LINES = 40;

    /** How many of the slowest test classes MEASURES names. */
    private static final int MAX_SLOW_SUITES = 5;

    /** The prefix of a measure key the receipt renders as a byte count. */
    private static final String SIZE_PREFIX = "size.";

    /**
     * Renders what the session was like in numbers, then how each
     * ledger bound fared (ike-issues#1207).
     *
     * <p>The derived measures come first, one line each with the figure
     * in a fixed column; the sizes the ledger named and the measurements
     * the tests left follow, key by key. A bound's line carries the
     * colour of its verdict, and a violated bound points at ATTENTION,
     * where its finding is.</p>
     */
    private static void renderMeasures(StringBuilder out, MeasureReport report) {
        if (report == null || !report.hasContent()) {
            return;
        }
        Measures measures = report.measures();
        boolean violated = report.statuses().stream().anyMatch(MeasureStatus::violated);
        out.append("## ").append(violated ? YELLOW : BLUE).append(" MEASURES\n\n");
        List<String> rows = new ArrayList<>();
        warningsRow(measures, rows);
        testsRow(measures, rows);
        coverageRow(measures, rows);
        buildRow(measures, rows);
        int extra = 0;
        int listed = 0;
        for (Map.Entry<String, Double> entry : measures.values().entrySet()) {
            if (MeasureKey.of(entry.getKey()) != null) {
                continue;
            }
            if (listed >= MAX_MEASUREMENT_LINES) {
                extra++;
                continue;
            }
            listed++;
            String value = entry.getKey().startsWith(SIZE_PREFIX)
                    ? Numbers.bytes(entry.getValue())
                    : Numbers.plain(entry.getValue());
            rows.add(row(value, entry.getKey()));
        }
        if (!rows.isEmpty()) {
            out.append("```\n");
            for (String line : rows) {
                out.append(line).append('\n');
            }
            if (extra > 0) {
                out.append(row("…", extra + " more in " + ReportSession.OBSERVATIONS_RELATIVE_PATH)).append('\n');
            }
            out.append("```\n\n");
        }
        if (!measures.slowest().isEmpty()) {
            out.append("Slowest test classes:\n\n```\n");
            int shown = Math.min(measures.slowest().size(), MAX_SLOW_SUITES);
            for (Measures.SlowSuite suite : measures.slowest().subList(0, shown)) {
                out.append(row(TestReports.formatSeconds(suite.seconds()), suite.module() + " · " + suite.suite()))
                        .append('\n');
            }
            out.append("```\n\n");
        }
        if (!report.statuses().isEmpty()) {
            out.append("Ledger bounds:\n\n");
            for (MeasureStatus status : report.statuses()) {
                renderBound(out, status);
            }
            out.append('\n');
        }
        if (report.published() > 0) {
            out.append("Published to TeamCity as ").append(report.published()).append(" build statistic(s).\n\n");
        }
        if (!measures.notes().isEmpty()) {
            out.append("Not measured:\n\n");
            for (String note : measures.notes()) {
                out.append("- ").append(note).append('\n');
            }
            out.append('\n');
        }
    }

    private static void renderBound(StringBuilder out, MeasureStatus status) {
        MeasureEntry entry = status.entry();
        out.append("- ");
        if (!status.measured()) {
            out.append(NEUTRAL).append(" `").append(entry.key()).append("` not measured this session (")
                    .append(entry.describe()).append(')');
        } else if (status.violated()) {
            out.append(RED).append(" `").append(entry.key()).append("` ").append(Numbers.plain(status.observed()))
                    .append(" — outside ").append(entry.describe()).append(", see ATTENTION");
        } else if (status.canTighten()) {
            out.append(BLUE).append(" `").append(entry.key()).append("` ").append(Numbers.plain(status.observed()))
                    .append(" within ").append(entry.describe()).append(" — can tighten to ")
                    .append(Numbers.plain(status.tightened()));
        } else {
            out.append(GREEN).append(" `").append(entry.key()).append("` ").append(Numbers.plain(status.observed()))
                    .append(" within ").append(entry.describe());
        }
        if (!entry.reason().isBlank()) {
            out.append(" — ").append(entry.reason());
        }
        out.append('\n');
    }

    private static String row(String value, String text) {
        return String.format(java.util.Locale.ROOT, "%9s  %s", value, text);
    }

    private static void warningsRow(Measures measures, List<String> rows) {
        Double total = measures.value(MeasureKey.WARNINGS_TOTAL);
        if (total == null) {
            return;
        }
        StringBuilder text = new StringBuilder("warnings");
        String separator = " — ";
        for (MeasureKey kind : MeasureCollector.warningKinds()) {
            Double count = measures.value(kind);
            if (count == null || count == 0) {
                continue;
            }
            text.append(separator).append(Numbers.plain(count)).append(' ').append(kindLabel(kind));
            separator = " · ";
        }
        Double errors = measures.value(MeasureKey.ERRORS_CONSOLE);
        if (errors != null && errors > 0) {
            text.append(" · ").append(Numbers.plain(errors)).append(" error line(s)");
        }
        rows.add(row(Numbers.plain(total), text.toString()));
    }

    private static String kindLabel(MeasureKey kind) {
        return switch (kind) {
            case WARNINGS_COMPILER_DEPRECATION -> "deprecation";
            case WARNINGS_COMPILER_REMOVAL -> "removal";
            case WARNINGS_COMPILER_OTHER -> "other lint";
            case WARNINGS_JAVADOC -> "javadoc";
            case WARNINGS_DEPENDENCY -> "dependency analysis";
            case WARNINGS_STDERR -> "stderr";
            case WARNINGS_TESTS -> "skipped-test classes";
            default -> "other";
        };
    }

    private static void testsRow(Measures measures, List<String> rows) {
        Double run = measures.value(MeasureKey.TESTS_RUN);
        if (run == null) {
            return;
        }
        Double failed = measures.value(MeasureKey.TESTS_FAILED);
        Double skipped = measures.value(MeasureKey.TESTS_SKIPPED);
        Double seconds = measures.value(MeasureKey.TESTS_SECONDS);
        StringBuilder text = new StringBuilder("tests run");
        if (failed != null) {
            text.append(" · ").append(Numbers.plain(failed)).append(" failed");
        }
        if (skipped != null) {
            text.append(" · ").append(Numbers.plain(skipped)).append(" skipped");
        }
        if (seconds != null) {
            text.append(" · ").append(TestReports.formatSeconds(seconds)).append(" in tests");
        }
        rows.add(row(Numbers.plain(run), text.toString()));
    }

    private static void coverageRow(Measures measures, List<String> rows) {
        Double percent = measures.value(MeasureKey.COVERAGE_LINE_PERCENT);
        if (percent == null) {
            return;
        }
        StringBuilder text = new StringBuilder("lines covered");
        Double covered = measures.value(MeasureKey.COVERAGE_LINE_COVERED);
        Double total = measures.value(MeasureKey.COVERAGE_LINE_TOTAL);
        if (covered != null && total != null) {
            text.append(" — ").append(Numbers.plain(covered)).append(" of ").append(Numbers.plain(total));
        }
        Double branches = measures.value(MeasureKey.COVERAGE_BRANCH_PERCENT);
        if (branches != null) {
            text.append(" · ").append(TestReports.percent(branches)).append(" branches");
        }
        Double modules = measures.value(MeasureKey.COVERAGE_MODULES);
        if (modules != null) {
            text.append(" · ").append(Numbers.plain(modules)).append(" module(s) with execution data");
        }
        rows.add(row(TestReports.percent(percent), text.toString()));
    }

    private static void buildRow(Measures measures, List<String> rows) {
        Double seconds = measures.value(MeasureKey.BUILD_SECONDS);
        if (seconds == null) {
            return;
        }
        StringBuilder text = new StringBuilder("build");
        Double built = measures.value(MeasureKey.BUILD_MODULES_BUILT);
        if (built != null) {
            text.append(" · ").append(Numbers.plain(built)).append(" module(s) built");
        }
        Double failed = measures.value(MeasureKey.BUILD_MODULES_FAILED);
        if (failed != null && failed > 0) {
            text.append(", ").append(Numbers.plain(failed)).append(" failed");
        }
        Double threads = measures.value(MeasureKey.BUILD_THREADS);
        if (threads != null) {
            text.append(" · ").append(Numbers.plain(threads)).append(threads == 1 ? " thread" : " threads");
        }
        rows.add(row(TestReports.formatSeconds(seconds), text.toString()));
    }

    private static void renderFailures(StringBuilder out, List<Finding> failures) {
        if (failures.isEmpty()) {
            return;
        }
        out.append("## ").append(RED).append(" FAILURES\n\n");
        for (Finding finding : failures) {
            out.append("- `").append(finding.key()).append("` — ").append(finding.detail()).append('\n');
        }
        out.append('\n');
    }

    private static void renderAttention(StringBuilder out, List<LedgerEvaluation.AttentionItem> attention) {
        if (attention.isEmpty()) {
            return;
        }
        out.append("## ").append(YELLOW).append(" ATTENTION\n\n");
        for (LedgerEvaluation.AttentionItem item : attention) {
            if (item.category() == FindingCategory.MEASURE) {
                // A measure is one value against one bound, not a count of
                // occurrences: its finding's detail says it all.
                out.append("- `").append(item.key()).append("` — ").append(item.sample()).append('\n');
                continue;
            }
            out.append("- `").append(item.key()).append("` — observed ").append(item.observed());
            if (item.expected() == null) {
                out.append(", not accepted");
            } else {
                out.append(", accepted ").append(item.expected());
            }
            out.append('\n');
            renderEvidence(out, item);
            renderOccurrences(out, item.findings());
        }
        out.append('\n');
    }

    /**
     * Renders the structured context shared by an item's findings —
     * what the thing is, and whose it is.
     *
     * <p>Only identifying context is promoted here; the per-occurrence
     * subject and error stay with their occurrence lines, where the
     * count makes them meaningful.</p>
     */
    private static void renderEvidence(StringBuilder out, LedgerEvaluation.AttentionItem item) {
        if (item.findings().isEmpty()) {
            return;
        }
        Map<String, String> context = item.findings().get(0).context();
        String repositoryId = context.get(Finding.CONTEXT_REPOSITORY_ID);
        String url = context.get(Finding.CONTEXT_REPOSITORY_URL);
        if (repositoryId != null) {
            out.append("  - repository `").append(repositoryId).append('`');
            if (url != null && !url.isBlank()) {
                out.append(" at `").append(url).append('`');
            }
            out.append('\n');
        }
        String declaredBy = context.get(Finding.CONTEXT_DECLARED_BY);
        if (declaredBy != null && !declaredBy.isBlank()) {
            out.append("  - ").append(declaredBy).append('\n');
        }
    }

    private static void renderOccurrences(StringBuilder out, List<Finding> findings) {
        int shown = Math.min(findings.size(), MAX_OCCURRENCES);
        for (int index = 0; index < shown; index++) {
            String detail = findings.get(index).detail();
            if (detail.isBlank()) {
                continue;
            }
            out.append("  - occurrence ").append(index + 1).append('/').append(findings.size())
                    .append(" — ").append(detail).append('\n');
        }
        if (findings.size() > shown) {
            out.append("  - … and ").append(findings.size() - shown)
                    .append(" further occurrence(s)\n");
        }
    }

    /**
     * Renders the options a reader has for each family of finding
     * present, so the receipt closes the loop it opens.
     *
     * <p>A finding that gates a build is only actionable if the reader
     * knows the responses available; naming them here is what turns the
     * receipt from a verdict into an instruction (ike-issues#989).</p>
     */
    private static void renderRemediation(StringBuilder out, LedgerEvaluation evaluation) {
        Set<FindingCategory> categories = new LinkedHashSet<>();
        for (LedgerEvaluation.AttentionItem item : evaluation.attention()) {
            if (item.category() != null) {
                categories.add(item.category());
            }
        }
        for (Finding failure : evaluation.failures()) {
            categories.add(failure.category());
        }
        if (categories.isEmpty()) {
            return;
        }
        out.append("\n## WHAT TO DO\n\n");
        if (categories.contains(FindingCategory.REPOSITORY)) {
            out.append("Repository findings are transfer-level failures, not missing artifacts —\n");
            out.append("a repository the build could not talk to at all. Options, in the order\n");
            out.append("worth considering:\n\n");
            out.append("1. **Not yours** — when the repository was declared by a dependency's\n");
            out.append("   POM (the `declared by the POM of …` line above), no change in this\n");
            out.append("   workspace will remove it. Block it once, for every build, in\n");
            out.append("   `~/.m2/settings.xml`:\n\n");
            out.append("   ```xml\n");
            out.append("   <mirror>\n");
            out.append("     <id>block-EXAMPLE</id>\n");
            out.append("     <mirrorOf>EXAMPLE</mirrorOf>\n");
            out.append("     <name>unreachable; declared by a third-party POM</name>\n");
            out.append("     <url>http://0.0.0.0/</url>\n");
            out.append("     <blocked>true</blocked>\n");
            out.append("   </mirror>\n");
            out.append("   ```\n\n");
            out.append("2. **Yours** — fix the URL, the credentials, or the network path.\n");
            out.append("3. **Known and tolerated** — accept the key in `")
                    .append(ReportSession.LEDGER_RELATIVE_PATH)
                    .append("` with a\n   `count` and a `reason` that says why it is tolerable.\n\n");
        }
        if (categories.contains(FindingCategory.MODEL)) {
            out.append("Model findings are recomputed from the POMs themselves, not scraped\n");
            out.append("from Maven's log. Either change the declaration the finding names, or\n");
            out.append("accept the key in `").append(ReportSession.LEDGER_RELATIVE_PATH)
                    .append("` with a reason.\n\n");
        }
        if (categories.contains(FindingCategory.MEASURE)) {
            out.append("Measure findings are a session value outside a bound the ledger places\n");
            out.append("on it. Either bring the measure back — fewer warnings, fewer skips, more\n");
            out.append("coverage — or move the bound in `").append(ReportSession.LEDGER_RELATIVE_PATH)
                    .append("` with a reason.\nBounds only tighten mechanically, so loosening one is always a hand edit.\n\n");
        }
        if (categories.contains(FindingCategory.EXECUTION)) {
            out.append("Execution findings are build-breaking and are never absorbed by the\n");
            out.append("ledger — fix the failing goal.\n\n");
        }
        out.append("To let one invocation through without changing anything, add\n");
        out.append("`-D").append(ReportSession.GATE_SKIP_PROPERTY).append("=true`;\n");
        out.append("the skip is recorded in this receipt.\n\n");
    }

    private static void renderAccepted(StringBuilder out, List<LedgerEvaluation.AcceptedStatus> accepted) {
        if (accepted.isEmpty()) {
            return;
        }
        out.append("## ").append(BLUE).append(" ACCEPTED\n\n");
        for (LedgerEvaluation.AcceptedStatus status : accepted) {
            out.append("- `").append(status.entry().key()).append("` — expected ")
                    .append(status.entry().count()).append(", observed ").append(status.observed());
            if (!status.entry().reason().isBlank()) {
                out.append(" — ").append(status.entry().reason());
            }
            out.append('\n');
        }
        out.append('\n');
    }

    private static void renderRatchet(StringBuilder out, List<LedgerEvaluation.AcceptedStatus> ratchet) {
        if (ratchet.isEmpty()) {
            return;
        }
        out.append("## ").append(BLUE).append(" RATCHET\n\n");
        for (LedgerEvaluation.AcceptedStatus status : ratchet) {
            out.append("- `").append(status.entry().key()).append("` — expected ")
                    .append(status.entry().count()).append(", observed ").append(status.observed())
                    .append(" — ledger can tighten\n");
        }
        out.append('\n');
    }

    private static String marked(String marker, int count) {
        return count > 0 ? marker + " " : "";
    }

    private static void renderSummary(StringBuilder out, LedgerEvaluation evaluation, Console console) {
        out.append("## SUMMARY\n\n");
        // A marker only where the count is non-zero, so a clean line
        // stays quiet and colour always means "look here".
        int errors = console.lines(ConsoleMessages.Level.ERROR);
        int warnings = console.lines(ConsoleMessages.Level.WARNING);
        out.append(marked(RED, evaluation.failures().size())).append("failures: ")
                .append(evaluation.failures().size())
                .append(" · ").append(marked(YELLOW, evaluation.attention().size())).append("attention: ")
                .append(evaluation.attention().size())
                .append(" · ").append(marked(BLUE, evaluation.accepted().size())).append("accepted: ")
                .append(evaluation.accepted().size())
                .append(" · ").append(marked(BLUE, evaluation.ratchet().size())).append("ratchet: ")
                .append(evaluation.ratchet().size())
                .append(" · console: ");
        if (console.captured()) {
            out.append(marked(RED, errors)).append(errors).append(" error(s), ")
                    .append(marked(YELLOW, warnings)).append(warnings).append(" warning(s)");
            if (console.ignoredLines() > 0) {
                out.append(", ").append(console.ignoredLines()).append(" ignored");
            }
        } else {
            out.append("not captured");
        }
        out.append('\n');
    }
}
