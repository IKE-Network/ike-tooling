package network.ike.plugin;

import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;

/**
 * Register a project on the IKE Network org landing page.
 *
 * <p>This is the {@code -publish} counterpart of {@code ike:register-site-draft}
 * (which defaults to a draft preview). Use this goal to actually
 * register the project.
 *
 * <p>Usage: {@code mvn ike:register-site-publish}
 *
 * @see RegisterSiteDraftMojo
 */
@Mojo(name = "register-site-publish", projectRequired = false, aggregator = true)
public class RegisterSitePublishMojo extends RegisterSiteDraftMojo {

    /** Creates this goal instance. */
    public RegisterSitePublishMojo() {}

    @Override
    public void execute() throws MojoException {
        publish = true;
        super.execute();
    }
}
