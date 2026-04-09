package network.ike.plugin;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;

/**
 * Publish a pre-built Maven site to GitHub Pages.
 *
 * <p>This is the {@code -apply} counterpart of {@code ike:publish-site}
 * (which defaults to a dry-run preview). Use this goal to actually
 * publish the site.
 *
 * <p>Usage: {@code mvn ike:publish-site-apply}
 *
 * @see PublishSiteMojo
 */
@Mojo(name = "publish-site-apply", requiresProject = false, aggregator = true, threadSafe = true)
public class PublishSiteApplyMojo extends PublishSiteMojo {

    /** Creates this goal instance. */
    public PublishSiteApplyMojo() {}

    @Override
    public void execute() throws MojoExecutionException {
        dryRun = false;
        super.execute();
    }
}
