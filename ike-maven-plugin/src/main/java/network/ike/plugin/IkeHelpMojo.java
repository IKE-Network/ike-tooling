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
        for (IkeGoal goal : goals) {
            String name = goal.qualified();
            String padding = " ".repeat(Math.max(2, 34 - name.length()));
            getLog().info("  " + name + padding + goal.description());
        }
        getLog().info("");
        getLog().info(goals.size() + " goal(s). Most ship as a -draft / -publish "
                + "pair — -draft previews, -publish applies.");
        getLog().info("");
    }
}
