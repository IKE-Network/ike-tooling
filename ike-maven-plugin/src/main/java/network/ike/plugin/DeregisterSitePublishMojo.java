package network.ike.plugin;

import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;

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
@Mojo(name = "deregister-site-publish", projectRequired = false, aggregator = true)
public class DeregisterSitePublishMojo extends DeregisterSiteDraftMojo {

    /** Creates this goal instance. */
    public DeregisterSitePublishMojo() {}

    @Override
    public void execute() throws MojoException {
        publish = true;
        super.execute();
    }
}
