package network.ike.plugin;

import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;

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
@Mojo(name = "release-publish", projectRequired = false, aggregator = true)
public class ReleasePublishMojo extends ReleaseDraftMojo {

    /** Creates this goal instance. */
    public ReleasePublishMojo() {}

    @Override
    public void execute() throws MojoException {
        publish = true;
        super.execute();
    }
}
