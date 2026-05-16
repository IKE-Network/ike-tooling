package network.ike.plugin.support;

import org.apache.maven.api.Session;
import org.apache.maven.api.di.Inject;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.Mojo;
import org.apache.maven.api.plugin.MojoException;

/**
 * Common base class for IKE plugin mojos. Implements {@code execute()}
 * as a {@code final} template: every goal does its work in
 * {@link #runGoal()} and returns a {@link GoalReportSpec}, and the
 * base class writes exactly one markdown session report to
 * {@code <projectRoot>/<prefix>꞉<goal>.md} (e.g.
 * {@code ike꞉release-publish.md}, {@code idoc꞉asciidoc.md}). Because
 * the report is a required return value, a goal cannot be written
 * that silently produces none (IKE-Network/ike-issues#413, #407).
 *
 * <p>The report writer self-heals {@code .gitignore} to keep reports
 * out of source control — see {@link GoalReport}. A single base class
 * serves every IKE plugin because goals identify themselves via the
 * {@link GoalRef} interface, not a plugin-specific enum type.
 *
 * <p>See <a href="https://github.com/IKE-Network/ike-issues/issues/215">
 * ike-issues #215</a> for the split that introduced this class.
 */
public abstract class AbstractGoalMojo implements Mojo {

    /** Default constructor — subclasses are instantiated by Maven's DI. */
    protected AbstractGoalMojo() {}

    @Inject
    private Log log;

    /** Maven session — consulted for interactive mode (#385). */
    @Inject
    private Session session;

    /** Interactive prompter, lazily built (IKE-Network/ike-issues#385). */
    private IkePrompter prompter;

    /**
     * Access the Maven logger injected by Maven 4's plugin DI.
     *
     * @return the injected logger
     */
    protected Log getLog() {
        return log;
    }

    /**
     * The {@link IkePrompter} for this goal — built lazily from the
     * session's interactive flag (IKE-Network/ike-issues#385).
     * Renders an inline prompt on a real terminal, an own-line prompt
     * in a piped IDE runner, and declines in batch mode.
     *
     * @return the prompter (never {@code null})
     */
    protected IkePrompter getPrompter() {
        if (prompter == null) {
            // No session (unit tests) or batch mode -> not interactive,
            // so the prompter declines rather than blocking on stdin.
            boolean interactive = session != null
                    && session.getSettings().isInteractiveMode();
            prompter = new ConsoleIkePrompter(getLog(), interactive);
        }
        return prompter;
    }

    /**
     * Inject an {@link IkePrompter} (typically a
     * {@link ScriptedIkePrompter}) for tests.
     *
     * @param prompter the prompter implementation to use
     */
    void setPrompter(IkePrompter prompter) {
        this.prompter = prompter;
    }

    /**
     * Run the goal and write its report.
     *
     * <p>This method is {@code final}: every IKE goal follows the same
     * template — do the work, then write exactly one report. Subclasses
     * supply the work and the report content by implementing
     * {@link #runGoal()}; they cannot override {@code execute()} to
     * skip the report. That is what makes report-writing structural —
     * a goal that compiles necessarily writes a report (the #407 bug
     * class becomes impossible; IKE-Network/ike-issues#413).
     *
     * @throws MojoException if the goal fails
     */
    @Override
    public final void execute() throws MojoException {
        GoalReportSpec report = runGoal();
        GoalReport.write(report.projectRoot(), report.goal(),
                report.content(), getLog());
    }

    /**
     * Run this goal's work and return the report it produced.
     *
     * <p>Implementations do the goal's actual work here and return a
     * {@link GoalReportSpec} — the goal identity, the directory the
     * report file lands in, and the Markdown body. The base class
     * writes it. A goal cannot be implemented without producing a
     * report, which is the structural fix for the missing-report bug
     * class (IKE-Network/ike-issues#413 / #407).
     *
     * <p>On failure, throw {@link MojoException} as usual — a failed
     * goal produces no report, and Maven surfaces the exception.
     *
     * @return the report this goal produced (never {@code null})
     * @throws MojoException if the goal fails
     */
    protected abstract GoalReportSpec runGoal() throws MojoException;
}
