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
// projectRequired = true: the goal needs Maven's active project so the
// @Parameter defaultValue templates ${project.name} and ${project.description}
// interpolate. With projectRequired = false they evaluated to literal "null"
// and the org-site fragment shipped `= null` as its heading.
@Mojo(name = "register-site-draft", projectRequired = true, aggregator = true)
public class RegisterSiteDraftMojo implements org.apache.maven.api.plugin.Mojo {

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
     * and src/site/ tree where the per-project fragment lands).
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
     * Human-readable project name. Defaults to the project's
     * {@code <name>} read directly from {@code pom.xml}. The
     * {@code defaultValue = "${project.name}"} form does NOT work
     * here — under Maven 4 with {@code aggregator = true}, the
     * project-property template doesn't interpolate at parameter-
     * injection time and the field arrives as a literal {@code null}.
     * Read from disk in {@link #execute()} instead.
     */
    @Parameter(property = "projectName")
    private String projectName;

    /**
     * One-line project description. Same caveat as
     * {@link #projectName} — read from {@code pom.xml} in
     * {@link #execute()} when not explicitly supplied.
     */
    @Parameter(property = "projectDescription")
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

        // Fill in name/description from pom.xml directly. The
        // @Parameter defaultValue route ($\{project.name},
        // $\{project.description}) doesn't work under
        // projectRequired=true + aggregator=true in Maven 4 — the
        // fields land as null and the org-site fragment shipped
        // `= null` as its heading. Read on demand instead.
        if (projectName == null || projectName.isBlank()) {
            projectName = readPomTextField(rootPom, "name");
            if (projectName == null || projectName.isBlank()) {
                // Fall back to a derived label so the org-site is
                // never headed with a literal "null".
                projectName = artifactId;
            }
        }
        if (projectDescription == null || projectDescription.isBlank()) {
            projectDescription = readPomTextField(rootPom, "description");
        }

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
        getLog().info("  Src repo:    " + srcRepo + " (" + srcBranch + ")");
        getLog().info("  Pub repo:    " + pubRepo + " (" + pubBranch + ")");
        getLog().info("  Modules:     " + (modules.isEmpty() ? "(none)" : modules));
        getLog().info("  Publish:       "+ publish);
        getLog().info("");

        if (draft) {
            getLog().info("[DRAFT] Would register " + artifactId + " " + version
                    + " on " + srcRepo + " + " + pubRepo);
            return;
        }

        OrgSiteSupport.registerProject(gitRoot, getLog(),
                srcRepo, pubRepo, srcBranch, pubBranch,
                artifactId, projectName, projectDescription, version,
                projectSiteUrl, githubUrl, modules);

        getLog().info("");
        getLog().info("Registered " + artifactId + " " + version
                + " on ike.network");
    }

    /**
     * Read the top-level value of a POM child element (e.g.
     * {@code <name>}, {@code <description>}). Skips any preceding
     * {@code <parent>} block so we return the project's own value,
     * not the inherited one. Returns {@code null} when the field
     * is absent or the file cannot be read.
     */
    static String readPomTextField(File pom, String fieldName) {
        if (pom == null || !pom.isFile()) return null;
        try {
            String content = java.nio.file.Files.readString(pom.toPath(),
                    java.nio.charset.StandardCharsets.UTF_8);
            int searchFrom = 0;
            int parentOpen = content.indexOf("<parent>");
            if (parentOpen >= 0) {
                int parentClose = content.indexOf("</parent>", parentOpen);
                if (parentClose > parentOpen) {
                    searchFrom = parentClose + "</parent>".length();
                }
            }
            String openTag = "<" + fieldName + ">";
            String closeTag = "</" + fieldName + ">";
            int open = content.indexOf(openTag, searchFrom);
            if (open < 0) return null;
            int valueStart = open + openTag.length();
            int close = content.indexOf(closeTag, valueStart);
            if (close < 0) return null;
            // Collapse whitespace so multi-line descriptions render
            // as a single line in the org-site fragment.
            return content.substring(valueStart, close)
                    .trim().replaceAll("\\s+", " ");
        } catch (java.io.IOException e) {
            return null;
        }
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
     * Handles both Maven 3 {@code <module>} and Maven 4
     * {@code <subproject>} tags. Returns an empty list for
     * non-reactor projects.
     */
    private List<String> resolveModules(File gitRoot) {
        // Parse modules from root POM — simple regex scan
        File rootPom = new File(gitRoot, "pom.xml");
        try {
            String pom = java.nio.file.Files.readString(rootPom.toPath(),
                    java.nio.charset.StandardCharsets.UTF_8);
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                    "<(?:module|subproject)>([^<]+)</(?:module|subproject)>");
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
