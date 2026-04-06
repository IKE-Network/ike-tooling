package network.ike.plugin;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;

/**
 * Execute the full release workflow.
 *
 * <p>This is the {@code -apply} counterpart of {@code ike:release}
 * (which defaults to a dry-run preview). Use this goal to actually
 * perform the release.
 *
 * <p>Usage: {@code mvn ike:release-apply}
 *
 * @see ReleaseMojo
 */
@Mojo(name = "release-apply", requiresProject = false, aggregator = true, threadSafe = true)
public class ReleaseApplyMojo extends ReleaseMojo {

    @Override
    public void execute() throws MojoExecutionException {
        dryRun = false;
        super.execute();
    }
}
