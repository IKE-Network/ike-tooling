package network.ike.plugin.support;

import org.apache.maven.api.Session;
import org.apache.maven.api.di.Inject;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.Mojo;

import java.nio.file.Path;

/**
 * Common base class for IKE plugin mojos. Provides the injected Maven
 * logger and a {@link #writeReport(GoalRef, Path, String)} helper so
 * every goal can emit a markdown session report to
 * {@code <projectRoot>/<prefix>꞉<goal>.md} (e.g.
 * {@code ike꞉release-publish.md}, {@code idoc꞉asciidoc.md}).
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
     * Write a goal's report to its per-goal file at the given project
     * root. Overwrites any previous content and self-heals
     * {@code .gitignore} in the nearest git ancestor.
     *
     * @param goal        the goal whose output is being reported
     * @param projectRoot the Maven project root the goal executed from
     * @param content     markdown content to write
     */
    protected void writeReport(GoalRef goal, Path projectRoot,
                                String content) {
        GoalReport.write(projectRoot, goal, content, getLog());
    }
}
