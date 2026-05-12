package network.ike.plugin;

import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

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
@Mojo(name = "deregister-site-draft", projectRequired = false, aggregator = true)
public class DeregisterSiteDraftMojo implements org.apache.maven.api.plugin.Mojo {

    @org.apache.maven.api.di.Inject
    private org.apache.maven.api.plugin.Log log;
    /**
     * Access the Maven logger.
     *
     * @return the logger
     */
    protected org.apache.maven.api.plugin.Log getLog() { return log; }

    /**
     * Git URL of the org-site SOURCE repository (has the Maven pom
     * and src/site/ tree where the per-project fragment to delete
     * lives).
     */
    @Parameter(property = "srcRepo",
               defaultValue = "https://github.com/IKE-Network/ike-network-site.git")
    private String srcRepo;

    /** Branch for source content in the source repo. */
    @Parameter(property = "srcBranch", defaultValue = "main")
    private String srcBranch;

    /**
     * Git URL of the org-site PUBLISH repository (rendered HTML
     * served at https://ike.network/).
     */
    @Parameter(property = "pubRepo",
               defaultValue = "https://github.com/IKE-Network/IKE-Network.github.io.git")
    private String pubRepo;

    /** Branch for rendered content in the publish repo. */
    @Parameter(property = "pubBranch", defaultValue = "main")
    private String pubBranch;

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
    public void execute() throws MojoException {
        boolean draft = !publish;

        getLog().info("");
        getLog().info("DEREGISTER PROJECT FROM IKE NETWORK");
        getLog().info("  Project ID:  " + projectId);
        getLog().info("  Src repo:    " + srcRepo + " (" + srcBranch + ")");
        getLog().info("  Pub repo:    " + pubRepo + " (" + pubBranch + ")");
        getLog().info("  Publish:       "+ publish);
        getLog().info("");

        if (draft) {
            getLog().info("[DRAFT] Would deregister " + projectId
                    + " from " + srcRepo + " + " + pubRepo);
            return;
        }

        OrgSiteSupport.deregisterProject(getLog(),
                srcRepo, pubRepo, srcBranch, pubBranch, projectId);

        getLog().info("");
        getLog().info("Deregistered " + projectId + " from ike.network");
    }
}
