package network.ike.plugin;

import network.ike.plugin.reconcile.SiteContext;
import network.ike.plugin.reconcile.SiteDriftReport;
import network.ike.plugin.reconcile.SiteReconciler;
import network.ike.plugin.reconcile.SiteReconcilerOptions;
import network.ike.plugin.reconcile.SiteReconcilerRegistry;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Diagnostic that reports drift in the project's deployed-site state.
 *
 * <p>Reads the current deployed site at
 * {@code https://ike.network/<artifactId>/} and the project's entry on
 * the IKE Network landing page, compares them to the current POM
 * version, and prints a drift report with copy-paste opt-out commands
 * inline.
 *
 * <p>Read-only — no remote mutation. Use {@link IkeSitePublishMojo}
 * to apply.
 *
 * <p>Subsumes the retired {@code ike:deploy-site-draft},
 * {@code ike:register-site-draft}, {@code ike:deregister-site-draft},
 * and {@code ike:clean-site} (in their preview-only roles) goals. See
 * IKE-Network/ike-issues#398.
 *
 * <p>Usage:
 * <pre>{@code
 * mvn ike:site-draft        # report deployed-site drift
 * mvn ike:site-publish      # apply (deploy + register)
 * }</pre>
 *
 * @see IkeSitePublishMojo
 */
@Mojo(name = IkeGoal.NAME_SITE_DRAFT, projectRequired = true, aggregator = true)
public class IkeSiteDraftMojo implements org.apache.maven.api.plugin.Mojo {

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
     * and src/site/ tree where per-project fragments live).
     */
    @Parameter(property = "srcRepo",
               defaultValue = "https://github.com/IKE-Network/ike-network-site.git")
    String srcRepo;

    /** Branch for source content in the source repo. */
    @Parameter(property = "srcBranch", defaultValue = "main")
    String srcBranch;

    /**
     * Git URL of the org-site PUBLISH repository (rendered HTML
     * served at https://ike.network/).
     */
    @Parameter(property = "pubRepo",
               defaultValue = "https://github.com/IKE-Network/IKE-Network.github.io.git")
    String pubRepo;

    /** Branch for rendered content in the publish repo. */
    @Parameter(property = "pubBranch", defaultValue = "main")
    String pubBranch;

    /**
     * Human-readable project name. Defaults to the project's POM
     * {@code <name>}. Read at execute time because
     * {@code defaultValue = "${project.name}"} does not interpolate
     * under Maven 4 with {@code aggregator = true} (the field arrives
     * as a literal {@code null} — same bug the retired
     * {@code RegisterSiteDraftMojo} worked around before #398).
     */
    @Parameter(property = "projectName")
    String projectName;

    /** One-line project description. Same caveat as {@link #projectName}. */
    @Parameter(property = "projectDescription")
    String projectDescription;

    /** Site URL on ike.network. Derived from artifact ID if not set. */
    @Parameter(property = "projectSiteUrl")
    String projectSiteUrl;

    /** GitHub repository URL. Derived from artifact ID if not set. */
    @Parameter(property = "githubUrl")
    String githubUrl;

    /** Version to reconcile against. Defaults to release version (SNAPSHOT stripped). */
    @Parameter(property = "releaseVersion")
    String releaseVersion;

    /**
     * Internal toggle: {@code true} for {@code site-publish},
     * {@code false} for {@code site-draft}.
     */
    @Parameter(property = "publish", defaultValue = "false")
    boolean publish;

    /** Creates this goal instance. */
    public IkeSiteDraftMojo() {}

    @Override
    public void execute() throws MojoException {
        SiteContext ctx = buildContext();

        getLog().info("");
        getLog().info(publish ? IkeGoal.SITE_PUBLISH.qualified()
                : IkeGoal.SITE_DRAFT.qualified());
        getLog().info("══════════════════════════════════════════════════════════════");
        getLog().info("  Project:        " + ctx.projectId());
        getLog().info("  Version:        " + ctx.projectVersion());
        getLog().info("  Site URL:       " + ctx.projectSiteUrl());
        if (publish && ctx.options().isUninstall()) {
            getLog().info("  Mode:           uninstall (-Dsite=removed)");
        }
        getLog().info("");

        if (publish) {
            runPublish(ctx);
        } else {
            runDraft(ctx);
        }
    }

    /** Iterate reconcilers in detect-mode and render reports. */
    private void runDraft(SiteContext ctx) {
        int driftCount = 0;
        for (SiteReconciler reconciler : SiteReconcilerRegistry.all()) {
            SiteDriftReport report = reconciler.detect(ctx);
            printDriftReport(report);
            if (report.hasDrift()) driftCount++;
        }
        getLog().info("");
        getLog().info("══════════════════════════════════════════════════════════════");
        if (driftCount == 0) {
            getLog().info("  No drift detected.");
        } else {
            getLog().info("  " + driftCount + " dimension(s) drift; "
                    + "run " + IkeGoal.SITE_PUBLISH.qualified()
                    + " to apply.");
        }
    }

    /** Iterate reconcilers in apply-mode (or uninstall when -Dsite=removed). */
    private void runPublish(SiteContext ctx) {
        if (ctx.options().isUninstall()) {
            for (SiteReconciler reconciler : SiteReconcilerRegistry.uninstallOrder()) {
                reconciler.uninstall(ctx);
            }
        } else {
            for (SiteReconciler reconciler : SiteReconcilerRegistry.all()) {
                reconciler.apply(ctx);
            }
        }
        getLog().info("");
        getLog().info("══════════════════════════════════════════════════════════════");
        getLog().info("  Done.");
    }

    /**
     * Build the {@link SiteContext} from current Mojo parameters and
     * the project's POM. Mirrors the parameter-resolution logic of the
     * retired {@code RegisterSiteDraftMojo}.
     */
    private SiteContext buildContext() throws MojoException {
        File gitRoot = ReleaseSupport.gitRoot(new File("."));
        File rootPom = new File(gitRoot, "pom.xml");
        String projectId = ReleaseSupport.readPomArtifactId(rootPom);

        String version;
        if (releaseVersion != null && !releaseVersion.isBlank()) {
            version = releaseVersion;
        } else {
            String pomVersion = ReleaseSupport.readPomVersion(rootPom);
            version = pomVersion.replace("-SNAPSHOT", "");
        }

        String resolvedName = projectName;
        if (resolvedName == null || resolvedName.isBlank()) {
            resolvedName = ReleaseSupport.readPomName(rootPom);
            if (resolvedName == null || resolvedName.isBlank()) {
                resolvedName = projectId;
            }
        }

        String resolvedDescription = projectDescription;
        if (resolvedDescription == null || resolvedDescription.isBlank()) {
            resolvedDescription = ReleaseSupport.readPomDescription(rootPom);
        }

        String resolvedSiteUrl = projectSiteUrl;
        if (resolvedSiteUrl == null || resolvedSiteUrl.isBlank()) {
            resolvedSiteUrl = "https://ike.network/" + projectId + "/";
        }

        String resolvedGithub = githubUrl;
        if (resolvedGithub == null || resolvedGithub.isBlank()) {
            resolvedGithub = "https://github.com/IKE-Network/" + projectId;
        }

        return new SiteContext(
                gitRoot, projectId, version,
                resolvedName, resolvedDescription,
                resolvedSiteUrl, resolvedGithub,
                srcRepo, srcBranch, pubRepo, pubBranch,
                readReconcilerOptions(), getLog());
    }

    /**
     * Collect Maven system properties into a {@link SiteReconcilerOptions}
     * bag so reconcilers can query their opt-out flags
     * (e.g., {@code -DupdateSite=false}) and the {@code -Dsite=removed}
     * uninstall switch.
     */
    private static SiteReconcilerOptions readReconcilerOptions() {
        Map<String, String> flags = new HashMap<>();
        for (String name : System.getProperties().stringPropertyNames()) {
            flags.put(name, System.getProperty(name));
        }
        return new SiteReconcilerOptions(flags);
    }

    /**
     * Render a {@link SiteDriftReport} for {@code site-draft} output
     * with the copy-paste opt-out command inline. Identical formatting
     * shape to {@code ws:scaffold-draft}'s {@code printDriftReport}.
     */
    private void printDriftReport(SiteDriftReport report) {
        getLog().info("");
        if (!report.hasDrift()) {
            getLog().info("  ✓ " + report.dimension());
            return;
        }
        getLog().info("  ⚠ " + report.dimension());
        if (!report.summary().isEmpty()) {
            getLog().info("     " + report.summary());
        }
        for (String line : report.detailLines()) {
            getLog().info("       " + line);
        }
        if (!report.defaultAction().isEmpty()) {
            getLog().info("     Default: " + report.defaultAction());
        }
        if (!report.optOutCommand().isEmpty()) {
            getLog().info("     Opt out: " + report.optOutCommand());
        }
    }
}
