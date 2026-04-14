package network.ike.plugin;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;

/**
 * Remove a project from the IKE Network org landing page.
 *
 * <p>This goal deletes the AsciiDoc registration fragment for the
 * specified project from the {@code IKE-Network.github.io} repository,
 * regenerates the master index, rebuilds the site, and publishes the
 * updated result to GitHub Pages.
 *
 * <p>By default this goal runs as a <strong>draft preview</strong>.
 * Use {@code ike:deregister-site-publish} to execute, or pass
 * {@code -Dpublish=true} explicitly.
 *
 * <p>Usage:
 * <pre>
 * # Preview what would happen:
 * mvn ike:deregister-site-draft
 *
 * # Actually deregister:
 * mvn ike:deregister-site-publish
 *
 * # Deregister a different project:
 * mvn ike:deregister-site-publish -DprojectId=ike-tooling
 * </pre>
 *
 * @see RegisterSiteDraftMojo
 * @see OrgSiteSupport
 */
@Mojo(name = "deregister-site-draft", requiresProject = false, aggregator = true, threadSafe = true)
public class DeregisterSiteDraftMojo extends AbstractMojo {

    /** Git URL of the org site repository. */
    @Parameter(property = "orgRepo",
               defaultValue = "https://github.com/IKE-Network/IKE-Network.github.io.git")
    private String orgRepo;

    /** Branch for source content in the org repo. */
    @Parameter(property = "orgBranch", defaultValue = "main")
    private String orgBranch;

    /**
     * Artifact ID of the project to deregister.
     * Defaults to the current project's artifact ID.
     */
    @Parameter(property = "projectId", defaultValue = "${project.artifactId}")
    private String projectId;

    /** Execute the deregistration (default: false = draft preview). */
    @Parameter(property = "publish", defaultValue = "false")
    boolean publish;

    /** Creates this goal instance. */
    public DeregisterSiteDraftMojo() {}

    @Override
    public void execute() throws MojoExecutionException {
        boolean draft = !publish;

        getLog().info("");
        getLog().info("DEREGISTER PROJECT FROM IKE NETWORK");
        getLog().info("  Project ID:  " + projectId);
        getLog().info("  Org repo:    " + orgRepo);
        getLog().info("  Org branch:  " + orgBranch);
        getLog().info("  Publish:       "+ publish);
        getLog().info("");

        if (draft) {
            getLog().info("[DRAFT] Would deregister " + projectId
                    + " from " + orgRepo);
            return;
        }

        OrgSiteSupport.deregisterProject(Maven4LogAdapter.wrap(getLog()), orgRepo, orgBranch, projectId);

        getLog().info("");
        getLog().info("Deregistered " + projectId + " from ike.network");
    }
}
