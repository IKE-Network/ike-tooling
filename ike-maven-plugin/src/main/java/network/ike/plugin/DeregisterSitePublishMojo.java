package network.ike.plugin;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;

/**
 * Remove a project from the IKE Network org landing page.
 *
 * <p>This is the {@code -publish} counterpart of {@code ike:deregister-site-draft}
 * (which defaults to a draft preview). Use this goal to actually
 * deregister the project.
 *
 * <p>Usage: {@code mvn ike:deregister-site-publish}
 *
 * @see DeregisterSiteDraftMojo
 */
@Mojo(name = "deregister-site-publish", requiresProject = false, aggregator = true, threadSafe = true)
public class DeregisterSitePublishMojo extends DeregisterSiteDraftMojo {

    /** Creates this goal instance. */
    public DeregisterSitePublishMojo() {}

    @Override
    public void execute() throws MojoExecutionException {
        publish = true;
        super.execute();
    }
}
