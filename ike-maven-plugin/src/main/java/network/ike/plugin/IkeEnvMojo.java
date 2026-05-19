package network.ike.plugin;

import network.ike.plugin.support.AbstractGoalMojo;
import network.ike.plugin.support.GoalReportBuilder;
import network.ike.plugin.support.GoalReportSpec;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;

import java.io.Console;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Print runtime environment diagnostics — terminal/console capability,
 * stdin, and relevant system properties.
 *
 * <p>Investigative tooling for the interactive-prompt work
 * (IKE-Network/ike-issues#385). The decisive question for how an
 * {@code IkePrompter} should render is whether the runner gave Maven
 * a real terminal. {@link System#console()} answers it: non-null
 * means a real terminal (inline prompts work), null means piped
 * (degrade to an own-line label).
 *
 * <p>Run it from <strong>both</strong> IntelliJ's Maven tool window
 * and the Terminal tool window and compare the output — that
 * settles, by measurement, what each context actually provides.
 *
 * <p>Read-only. Usage: {@code mvn ike:env}
 */
@Mojo(name = IkeGoal.NAME_ENV, projectRequired = false, aggregator = true)
public class IkeEnvMojo extends AbstractGoalMojo {

    /** Creates this goal instance. */
    public IkeEnvMojo() {}

    @Override
    protected GoalReportSpec runGoal() throws MojoException {
        Console console = System.console();

        List<String> probe = new ArrayList<>();
        probe.add("System.console()        : "
                + (console != null
                        ? "PRESENT (real terminal)" : "null (piped)"));
        if (console != null) {
            String isTerminal;
            try {
                isTerminal = String.valueOf(console.isTerminal());
            } catch (Throwable t) {
                isTerminal = "(unavailable: "
                        + t.getClass().getSimpleName() + ")";
            }
            probe.add("Console.isTerminal()    : " + isTerminal);
            probe.add("Console.charset()       : " + console.charset());
        }
        probe.add("System.in class         : "
                + System.in.getClass().getName());
        probe.add("TERM                    : " + envOr("TERM"));
        probe.add("TERMINAL_EMULATOR       : "
                + envOr("TERMINAL_EMULATOR"));
        probe.add("style.color (sysprop)   : "
                + System.getProperty("style.color", "(unset)"));
        probe.add("os.name / java.version  : "
                + System.getProperty("os.name") + " / "
                + System.getProperty("java.version"));

        getLog().info("");
        getLog().info("IKE environment probe (ike-issues#385)");
        getLog().info(
                "──────────────────────────────────────────────────");
        for (String line : probe) {
            getLog().info("  " + line);
        }
        getLog().info(
                "──────────────────────────────────────────────────");
        getLog().info("  Run from BOTH the Maven tool window and the");
        getLog().info("  Terminal tool window; compare System.console().");
        getLog().info("  Non-null → real terminal, inline prompts work.");
        getLog().info("  null     → piped, prompt label needs its own line.");
        getLog().info("");

        String report = new GoalReportBuilder()
                .section("Environment probe")
                .paragraph("Runtime terminal/console capability — the"
                        + " decisive signal for how interactive prompts"
                        + " render (ike-issues#385).")
                .codeBlock("", String.join("\n", probe))
                .paragraph("`System.console()` non-null means a real"
                        + " terminal (inline prompts work); null means"
                        + " piped (degrade to an own-line label).")
                .build();
        return new GoalReportSpec(IkeGoal.ENV, Path.of("."), report);
    }

    private static String envOr(String name) {
        String value = System.getenv(name);
        return (value == null || value.isBlank()) ? "(unset)" : value;
    }
}
