package network.ike.plugin;

import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.File;
import java.util.List;

/**
 * Register a project on the IKE Network org landing page.
 *
 * <p>This goal writes an AsciiDoc registration fragment for the
 * current project into the {@code IKE-Network.github.io} repository,
 * regenerates the master index, builds the site through the full
 * AsciiDoc pipeline, and publishes the result to GitHub Pages.
 *
 * <p>Designed to be called as part of the release ceremony, after
 * {@code ike:deploy-site-publish} has pushed the project's own site to
 * its {@code gh-pages} branch. The org site registration is a
 * best-effort operation — failure does not block a release.
 *
 * <p>By default this goal runs as a <strong>draft preview</strong>.
 * Use {@code ike:register-site-publish} to execute, or pass
 * {@code -Dpublish=true} explicitly.
 *
 * <p>Usage:
 * <pre>
 * # Preview what would happen:
 * mvn ike:register-site-draft
 *
 * # Actually register:
 * mvn ike:register-site-publish
 * </pre>
 *
 * @see DeregisterSiteDraftMojo
 * @see OrgSiteSupport
 */
@Mojo(name = "register-site-draft", projectRequired = false, aggregator = true)
public class RegisterSiteDraftMojo implements org.apache.maven.api.plugin.Mojo {

    @org.apache.maven.api.di.Inject
    private org.apache.maven.api.plugin.Log log;
    /**
     * Access the Maven logger.
     *
     * @return the logger
     */
    protected org.apache.maven.api.plugin.Log getLog() { return log; }

    /** Git URL of the org site repository. */
    @Parameter(property = "orgRepo",
               defaultValue = "https://github.com/IKE-Network/IKE-Network.github.io.git")
    private String orgRepo;

    /** Branch for source content in the org repo. */
    @Parameter(property = "orgBranch", defaultValue = "main")
    private String orgBranch;

    /** Human-readable project name. Defaults to POM {@code <name>}. */
    @Parameter(property = "projectName", defaultValue = "${project.name}")
    private String projectName;

    /** One-line project description. Defaults to POM {@code <description>}. */
    @Parameter(property = "projectDescription", defaultValue = "${project.description}")
    private String projectDescription;

    /** Site URL on ike.network. Derived from artifact ID if not set. */
    @Parameter(property = "projectSiteUrl")
    private String projectSiteUrl;

    /** GitHub repository URL. Derived from SCM or project URL if not set. */
    @Parameter(property = "githubUrl")
    private String githubUrl;

    /** Version to register. Defaults to release version (SNAPSHOT stripped). */
    @Parameter(property = "releaseVersion")
    private String releaseVersion;

    /** Execute the registration (default: false = draft preview). */
    @Parameter(property = "publish", defaultValue = "false")
    boolean publish;

    /** Creates this goal instance. */
    public RegisterSiteDraftMojo() {}

    @Override
    public void execute() throws MojoException {
        File gitRoot = ReleaseSupport.gitRoot(new File("."));
        File rootPom = new File(gitRoot, "pom.xml");

        String artifactId = ReleaseSupport.readPomArtifactId(rootPom);
        String version = resolveVersion(rootPom);

        if (projectSiteUrl == null || projectSiteUrl.isBlank()) {
            projectSiteUrl = "https://ike.network/" + artifactId + "/";
        }
        if (githubUrl == null || githubUrl.isBlank()) {
            githubUrl = "https://github.com/IKE-Network/" + artifactId;
        }

        // Collect reactor module names
        List<String> modules = resolveModules(gitRoot);

        boolean draft = !publish;

        getLog().info("");
        getLog().info("REGISTER PROJECT ON IKE NETWORK");
        getLog().info("  Project:     " + artifactId);
        getLog().info("  Name:        " + projectName);
        getLog().info("  Version:     " + version);
        getLog().info("  Site URL:    " + projectSiteUrl);
        getLog().info("  GitHub:      " + githubUrl);
        getLog().info("  Org repo:    " + orgRepo);
        getLog().info("  Org branch:  " + orgBranch);
        getLog().info("  Modules:     " + (modules.isEmpty() ? "(none)" : modules));
        getLog().info("  Publish:       "+ publish);
        getLog().info("");

        if (draft) {
            getLog().info("[DRAFT] Would register " + artifactId + " " + version
                    + " on " + orgRepo);
            return;
        }

        OrgSiteSupport.registerProject(gitRoot, getLog(), orgRepo, orgBranch,
                artifactId, projectName, projectDescription, version,
                projectSiteUrl, githubUrl, modules);

        getLog().info("");
        getLog().info("Registered " + artifactId + " " + version
                + " on ike.network");
    }

    /**
     * Resolve the version to register. Prefers the explicit
     * {@code releaseVersion} parameter, falls back to POM version
     * with {@code -SNAPSHOT} stripped.
     */
    private String resolveVersion(File rootPom) throws MojoException {
        if (releaseVersion != null && !releaseVersion.isBlank()) {
            return releaseVersion;
        }
        String pomVersion = ReleaseSupport.readPomVersion(rootPom);
        return pomVersion.replace("-SNAPSHOT", "");
    }

    /**
     * Collect reactor module names from the root POM.
     * Returns an empty list for non-reactor projects.
     */
    private List<String> resolveModules(File gitRoot) {
        // Parse modules from root POM — simple regex scan
        File rootPom = new File(gitRoot, "pom.xml");
        try {
            String pom = java.nio.file.Files.readString(rootPom.toPath(),
                    java.nio.charset.StandardCharsets.UTF_8);
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                    "<module>([^<]+)</module>");
            java.util.regex.Matcher matcher = pattern.matcher(pom);
            List<String> modules = new java.util.ArrayList<>();
            while (matcher.find()) {
                modules.add(matcher.group(1));
            }
            return modules;
        } catch (Exception e) {
            getLog().debug("Could not parse modules from POM: " + e.getMessage());
            return List.of();
        }
    }
}
