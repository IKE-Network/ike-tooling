package network.ike.plugin;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;

/**
 * Deploy the Maven site to a versioned URL.
 *
 * <p>This is the {@code -apply} counterpart of {@code ike:deploy-site}
 * (which defaults to a dry-run preview). Use this goal to actually
 * deploy the site.
 *
 * <p>Usage: {@code mvn ike:deploy-site-apply -DsiteType=release}
 *
 * @see DeploySiteMojo
 */
@Mojo(name = "deploy-site-apply", requiresProject = false, aggregator = true, threadSafe = true)
public class DeploySiteApplyMojo extends DeploySiteMojo {

    /** Creates this goal instance. */
    public DeploySiteApplyMojo() {}

    @Override
    public void execute() throws MojoExecutionException {
        dryRun = false;
        super.execute();
    }
}
