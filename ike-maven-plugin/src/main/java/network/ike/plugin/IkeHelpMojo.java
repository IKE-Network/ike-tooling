package network.ike.plugin;

import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Displays the available {@code ike:} goals, enumerated from the
 * compile-time {@link IkeGoal} registry.
 *
 * <p>Goal names and descriptions come from the {@link IkeGoal} enum —
 * the single source of truth for every {@code ike:} goal in this
 * plugin — so the help output cannot drift from the actual plugin.
 * Mirrors {@code ws:help}, which enumerates {@code WsGoal} the same way.
 *
 * @see <a href="https://github.com/IKE-Network/ike-tooling">IKE Tooling</a>
 */
@Mojo(name = IkeGoal.NAME_HELP, projectRequired = false)
public class IkeHelpMojo implements org.apache.maven.api.plugin.Mojo {

    @org.apache.maven.api.di.Inject
    private org.apache.maven.api.plugin.Log log;
    /**
     * Access the Maven logger.
     *
     * @return the logger
     */
    protected org.apache.maven.api.plugin.Log getLog() { return log; }

    /** Creates this goal instance. */
    public IkeHelpMojo() {}

    @Override
    public void execute() throws MojoException {
        List<IkeGoal> goals = new ArrayList<>(List.of(IkeGoal.values()));
        goals.sort(Comparator.comparing(IkeGoal::goalName));

        getLog().info("");
        getLog().info("IKE Build Tools — Available Goals");
        getLog().info("══════════════════════════════════════════════════════════════");
        getLog().info("");
        boolean anyConsoleBacked = false;
        for (IkeGoal goal : goals) {
            String name = goal.qualified();
            String padding = " ".repeat(Math.max(2, 34 - name.length()));
            String console = consoleEquivalent(goal);
            String hint = (console == null) ? ""
                    : "  [engine — prefer " + console + "]";
            anyConsoleBacked |= console != null;
            getLog().info("  " + name + padding + goal.description() + hint);
        }
        getLog().info("");
        getLog().info(goals.size() + " goal(s). Most ship as a -draft / -publish "
                + "pair — -draft previews, -publish applies.");
        if (anyConsoleBacked) {
            getLog().info("");
            getLog().info("  [engine] goals are the per-repo engine for the "
                    + "ws: console — prefer the ws: command (it works in a");
            getLog().info("  single repo or a workspace); the ike: form runs "
                    + "underneath (ike-issues#601).");
        }
        getLog().info("");
    }

    /**
     * The {@code ws:} console command that is the typed entry point for
     * this goal, or {@code null} if it has none.
     *
     * <p>{@code ike:release-*} and {@code ike:scaffold-{draft,publish}} are
     * the per-repo engine that {@code ws:release} / {@code ws:scaffold}
     * invoke; the {@code ws:} command is transparent over a single repo and
     * a workspace (ike-issues#601), so it is the entry point a developer
     * types. {@code ike:release-cascade} (the foundation self-release) and
     * the build-lifecycle goals have no {@code ws:} equivalent and are not
     * demoted. Returned as a string because the {@code ike:} plugin does
     * not depend on the downstream {@code ws:} plugin.
     *
     * @param goal the ike goal
     * @return the {@code ws:} console command, or {@code null}
     */
    static String consoleEquivalent(IkeGoal goal) {
        return switch (goal) {
            case RELEASE_DRAFT, RELEASE_PUBLISH -> "ws:release";
            case SCAFFOLD_DRAFT, SCAFFOLD_PUBLISH -> "ws:scaffold";
            default -> null;
        };
    }
}
