package network.ike.plugin;

import org.apache.maven.api.di.Inject;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.Mojo;

import java.nio.file.Path;

/**
 * Common base class for {@code ike:*} goals. Provides the injected
 * Maven logger and a {@link #writeReport(IkeGoal, Path, String)} helper
 * so every goal can emit a markdown session report to
 * {@code <projectRoot>/ike꞉<goal>.md}.
 *
 * <p>Parallels {@code network.ike.plugin.ws.AbstractWorkspaceMojo} in
 * the ws plugin. See issue #169.
 */
public abstract class AbstractIkeMojo implements Mojo {

    /** Default constructor — subclasses are instantiated by Maven. */
    protected AbstractIkeMojo() {}

    @Inject
    private Log log;

    /**
     * Access the Maven logger.
     *
     * @return the injected logger
     */
    protected Log getLog() {
        return log;
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
    protected void writeReport(IkeGoal goal, Path projectRoot,
                                String content) {
        IkeReport.write(projectRoot, goal, content, getLog());
    }
}
