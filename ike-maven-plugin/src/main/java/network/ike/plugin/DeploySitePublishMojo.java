package network.ike.plugin;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;

/**
 * Deploy the Maven site to a versioned URL.
 *
 * <p>This is the {@code -publish} counterpart of {@code ike:deploy-site}
 * (which defaults to a draft preview). Use this goal to actually
 * deploy the site.
 *
 * <p>Usage: {@code mvn ike:deploy-site-publish -DsiteType=release}
 *
 * @see DeploySiteMojo
 */
@Mojo(name = "deploy-site-publish", requiresProject = false, aggregator = true, threadSafe = true)
public class DeploySitePublishMojo extends DeploySiteDraftMojo {

    /** Creates this goal instance. */
    public DeploySitePublishMojo() {}

    @Override
    public void execute() throws MojoExecutionException {
        publish = true;
        super.execute();
    }
}
