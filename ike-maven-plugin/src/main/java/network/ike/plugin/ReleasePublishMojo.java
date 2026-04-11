package network.ike.plugin;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;

/**
 * Execute the full release workflow.
 *
 * <p>This is the {@code -publish} counterpart of {@code ike:release}
 * (which defaults to a draft preview). Use this goal to actually
 * perform the release.
 *
 * <p>Usage: {@code mvn ike:release-publish}
 *
 * @see ReleaseDraftMojo
 */
@Mojo(name = "release-publish", requiresProject = false, aggregator = true, threadSafe = true)
public class ReleasePublishMojo extends ReleaseDraftMojo {

    /** Creates this goal instance. */
    public ReleasePublishMojo() {}

    @Override
    public void execute() throws MojoExecutionException {
        publish = true;
        super.execute();
    }
}
