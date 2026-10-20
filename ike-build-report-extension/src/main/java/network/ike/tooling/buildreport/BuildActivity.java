package network.ike.tooling.buildreport;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Records what a session built and how long it took — the work the
 * receipt summarizes before it lists anything that went wrong.
 *
 * <p>Fed by {@link BuildReportSpy} from project and mojo execution
 * events. Timing is this collector's own clock, started when the
 * session is reset at project discovery, so the wall time covers the
 * same span as Maven's {@code Total time} line. Under a parallel build
 * module times overlap and sum to more than the wall time; the receipt
 * says so rather than hiding it.</p>
 *
 * <p>The collector also answers "what is this thread building right
 * now?" ({@link #current()}), which is what lets a console warning be
 * attributed to the module and goal that produced it.</p>
 */
public final class BuildActivity {

    /** How one reactor module fared. */
    public enum ModuleResult {
        /** Every goal bound to the module ran and succeeded. */
        BUILT,
        /** The module's build has started and not yet ended. */
        BUILDING,
        /** A goal failed in the module. */
        FAILED,
        /** Maven skipped the module after a failure elsewhere. */
        SKIPPED,
        /** The module has not been started — yet, or ever. */
        NOT_BUILT
    }

    /**
     * One reactor module's outcome.
     *
     * @param name   the module's artifact id
     * @param result how the module fared
     * @param time   the module's build time, so far for a module still
     *               building; zero when it never ran
     * @param goal   the goal a building module is executing; empty otherwise
     */
    public record Module(String name, ModuleResult result, Duration time, String goal) {

        /**
         * Creates the outcome of a module that is not building.
         *
         * @param name   the module's artifact id
         * @param result how the module fared
         * @param time   the module's build time; zero when it never ran
         */
        public Module(String name, ModuleResult result, Duration time) {
            this(name, result, time, "");
        }
    }

    /**
     * Time spent in one plugin goal, summed across every module.
     *
     * @param goal       the goal as {@code plugin-artifact-id:goal}
     * @param executions how many times the goal ran
     * @param time       the total time across those executions
     */
    public record GoalTime(String goal, int executions, Duration time) {
    }

    /**
     * What a thread is building at one moment.
     *
     * @param module the module's artifact id; empty when unknown
     * @param goal   the goal as {@code plugin-artifact-id:goal}; empty
     *               when no goal is executing
     */
    public record Context(String module, String goal) {

        /** The context of a thread that is not building anything. */
        public static final Context NONE = new Context("", "");
    }

    /**
     * The session's work, frozen for rendering.
     *
     * @param goals        the goals and phases the invocation requested
     * @param profiles     the explicitly activated profiles
     * @param mavenVersion the running Maven's version; empty when unknown
     * @param javaVersion  the JDK Maven ran on
     * @param threads      the degree of concurrency; 1 for a serial build
     * @param wallTime     elapsed time from project discovery to session end
     * @param modules      every reactor module, in reactor order
     * @param goalTimes    time per plugin goal, slowest first
     */
    public record Overview(
            List<String> goals,
            List<String> profiles,
            String mavenVersion,
            String javaVersion,
            int threads,
            Duration wallTime,
            List<Module> modules,
            List<GoalTime> goalTimes) {

        /**
         * Counts the modules with one result.
         *
         * @param result the result to count
         * @return how many modules ended with that result
         */
        public long count(ModuleResult result) {
            return modules.stream().filter(module -> module.result() == result).count();
        }

        /**
         * Says whether any module failed or was left unbuilt.
         *
         * @return true when the session did not build everything it set out to
         */
        public boolean incomplete() {
            return modules.stream().anyMatch(module -> module.result() != ModuleResult.BUILT);
        }
    }

    private static final class ModuleState {
        private ModuleResult result = ModuleResult.NOT_BUILT;
        private boolean started;
        private long startedNanos;
        private long elapsedNanos;
    }

    private static final class GoalState {
        private int executions;
        private long elapsedNanos;
    }

    private record Running(String module, String goal, long startedNanos) {
    }

    private final LongSupplier clock;
    private final Map<String, ModuleState> modules = new LinkedHashMap<>();
    private final Map<String, GoalState> goalTimes = new LinkedHashMap<>();
    private final Map<Thread, Running> running = new ConcurrentHashMap<>();

    private List<String> goals = List.of();
    private List<String> profiles = List.of();
    private String mavenVersion = "";
    private int threads = 1;
    private long sessionStartedNanos;

    /** Creates a collector on the system clock. */
    public BuildActivity() {
        this(System::nanoTime);
    }

    /**
     * Creates a collector on a supplied clock, for tests.
     *
     * @param clock a monotonic nanosecond clock
     */
    BuildActivity(LongSupplier clock) {
        this.clock = clock;
        this.sessionStartedNanos = clock.getAsLong();
    }

    /** Clears all state and restarts the session clock. */
    public synchronized void reset() {
        modules.clear();
        goalTimes.clear();
        running.clear();
        goals = List.of();
        profiles = List.of();
        mavenVersion = "";
        threads = 1;
        sessionStartedNanos = clock.getAsLong();
    }

    /**
     * Records the invocation and the reactor it is about to build.
     *
     * @param moduleNames  every reactor module's artifact id, in reactor order
     * @param goals        the goals and phases requested
     * @param profiles     the explicitly activated profiles
     * @param mavenVersion the running Maven's version; null when unknown
     * @param threads      the degree of concurrency
     */
    public synchronized void sessionStarted(
            List<String> moduleNames,
            List<String> goals,
            List<String> profiles,
            String mavenVersion,
            int threads) {
        for (String name : moduleNames) {
            modules.computeIfAbsent(name, key -> new ModuleState());
        }
        this.goals = goals == null ? List.of() : List.copyOf(goals);
        this.profiles = profiles == null ? List.of() : List.copyOf(profiles);
        this.mavenVersion = mavenVersion == null ? "" : mavenVersion;
        this.threads = Math.max(1, threads);
    }

    /**
     * Records that a module's build began on the calling thread.
     *
     * @param module the module's artifact id
     */
    public synchronized void projectStarted(String module) {
        ModuleState state = modules.computeIfAbsent(module, key -> new ModuleState());
        state.started = true;
        state.startedNanos = clock.getAsLong();
        running.put(Thread.currentThread(), new Running(module, "", 0L));
    }

    /**
     * Records a module's outcome.
     *
     * @param module the module's artifact id
     * @param result how the module fared
     */
    public synchronized void projectFinished(String module, ModuleResult result) {
        ModuleState state = modules.computeIfAbsent(module, key -> new ModuleState());
        state.result = result;
        if (state.started) {
            state.elapsedNanos = clock.getAsLong() - state.startedNanos;
        }
        running.remove(Thread.currentThread());
    }

    /**
     * Records that a goal began in a module on the calling thread.
     *
     * @param module the module's artifact id
     * @param goal   the goal as {@code plugin-artifact-id:goal}
     */
    public void mojoStarted(String module, String goal) {
        running.put(Thread.currentThread(), new Running(module, goal, clock.getAsLong()));
    }

    /**
     * Records that the goal running on the calling thread ended,
     * successfully or not.
     */
    public synchronized void mojoFinished() {
        Running finished = running.get(Thread.currentThread());
        if (finished == null || finished.goal().isEmpty()) {
            return;
        }
        GoalState state = goalTimes.computeIfAbsent(finished.goal(), key -> new GoalState());
        state.executions++;
        state.elapsedNanos += clock.getAsLong() - finished.startedNanos();
        running.put(Thread.currentThread(), new Running(finished.module(), "", 0L));
    }

    /**
     * Says what the calling thread is building.
     *
     * <p>A plugin may log from a thread of its own — a forked JVM's
     * stream pump, for instance. When the calling thread is not a build
     * thread and exactly one module is in flight, that module is the
     * only candidate and is returned.</p>
     *
     * @return the calling thread's context, or {@link Context#NONE}
     */
    public Context current() {
        Running mine = running.get(Thread.currentThread());
        if (mine == null) {
            List<Running> all = List.copyOf(running.values());
            if (all.size() != 1) {
                return Context.NONE;
            }
            mine = all.get(0);
        }
        return new Context(mine.module(), mine.goal());
    }

    /**
     * Freezes the session's work for rendering.
     *
     * @return the overview as of now
     */
    public synchronized Overview snapshot() {
        long now = clock.getAsLong();
        List<Module> moduleList = new ArrayList<>();
        for (Map.Entry<String, ModuleState> entry : modules.entrySet()) {
            ModuleState state = entry.getValue();
            if (state.started && state.result == ModuleResult.NOT_BUILT) {
                String goal = "";
                for (Running active : running.values()) {
                    if (active.module().equals(entry.getKey())) {
                        goal = active.goal();
                    }
                }
                moduleList.add(new Module(entry.getKey(), ModuleResult.BUILDING,
                        Duration.ofNanos(now - state.startedNanos), goal));
            } else {
                moduleList.add(new Module(
                        entry.getKey(), state.result, Duration.ofNanos(state.elapsedNanos)));
            }
        }
        List<GoalTime> goalList = new ArrayList<>();
        for (Map.Entry<String, GoalState> entry : goalTimes.entrySet()) {
            GoalState state = entry.getValue();
            goalList.add(new GoalTime(
                    entry.getKey(), state.executions, Duration.ofNanos(state.elapsedNanos)));
        }
        goalList.sort(Comparator.comparing(GoalTime::time).reversed());
        return new Overview(
                goals,
                profiles,
                mavenVersion,
                System.getProperty("java.version", ""),
                threads,
                Duration.ofNanos(now - sessionStartedNanos),
                List.copyOf(moduleList),
                List.copyOf(goalList));
    }
}
