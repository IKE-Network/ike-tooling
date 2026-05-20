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
import java.util.Optional;

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

        // Version-to-register on the landing page. Three sources, in
        // priority order, each chosen so the recorded value matches an
        // actual artifact a visitor could resolve from Maven Central
        // (IKE-Network/ike-issues#465).
        //
        //   1. -DreleaseVersion=X — explicit override, used when the
        //      goal runs from the in-flight release script.
        //   2. Otherwise, if the POM is NOT at a SNAPSHOT, use the
        //      POM literal — that's the released version we just cut.
        //   3. Otherwise (working tree is SNAPSHOT), use the most
        //      recent `vN` tag — that's the last released version,
        //      not the next planned one. Pre-#465 the goal stripped
        //      `-SNAPSHOT` from the POM, which produced a value like
        //      `3` while only v2 was actually in Central.
        String version;
        if (releaseVersion != null && !releaseVersion.isBlank()) {
            version = releaseVersion;
        } else {
            String pomVersion = ReleaseSupport.readPomVersion(rootPom);
            if (!pomVersion.endsWith("-SNAPSHOT")) {
                version = pomVersion;
            } else {
                version = latestReleaseTag(gitRoot)
                        .orElse(pomVersion.replace("-SNAPSHOT", ""));
            }
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
     * Look up the latest released version tag in the project's git
     * history. Used by {@link #buildContext} when the working tree is
     * at a SNAPSHOT — the registered landing-page version must point at
     * an artifact Maven Central actually serves, not the next planned
     * version (IKE-Network/ike-issues#465).
     *
     * <p>Tags are matched against the pattern {@code v*}. The
     * {@code v} prefix is stripped from the returned value. Tag
     * ordering uses {@code --sort=-v:refname} so {@code v10} > {@code v9}
     * regardless of git's default lexical sort. Returns {@link Optional#empty}
     * for repositories with no {@code v*} tags — the caller then
     * falls back to the legacy {@code -SNAPSHOT}-stripped POM
     * version.
     *
     * @param gitRoot root of the calling project's git working tree
     * @return the latest released version (e.g. {@code "2"}), or
     *         empty if no {@code v*} tags exist
     */
    static Optional<String> latestReleaseTag(File gitRoot) {
        try {
            String out = ReleaseSupport.execCapture(gitRoot,
                    "git", "for-each-ref",
                    "--sort=-v:refname",
                    "--count=1",
                    "--format=%(refname:short)",
                    "refs/tags/v*");
            String tag = out == null ? "" : out.trim();
            if (tag.isEmpty()) {
                return Optional.empty();
            }
            if (tag.startsWith("v")) {
                tag = tag.substring(1);
            }
            return tag.isEmpty() ? Optional.empty() : Optional.of(tag);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
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
