package network.ike.plugin;

import network.ike.plugin.support.GoalReportSpec;
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
@Mojo(name = IkeGoal.NAME_RELEASE_PUBLISH, projectRequired = false, aggregator = true)
public class ReleasePublishMojo extends ReleaseDraftMojo {

    /** Creates this goal instance. */
    public ReleasePublishMojo() {}

    @Override
    protected GoalReportSpec runGoal() throws MojoException {
        publish = true;
        return super.runGoal();
    }
}
