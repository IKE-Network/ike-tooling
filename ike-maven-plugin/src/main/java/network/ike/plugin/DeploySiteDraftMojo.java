package network.ike.plugin;

import network.ike.plugin.support.MojoParamSupport;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.File;
import java.nio.file.Path;

/**
 * Generate and deploy the Maven site to a versioned URL.
 *
 * <p>This goal deploys the project site to one of three location
 * types under {@code ike.komet.sh}:
 * <ul>
 *   <li>{@code release} — version-prefixed
 *       ({@code <projectId>/<version>/}); a {@code latest} symlink
 *       points at the most recent release (ike-issues#303)</li>
 *   <li>{@code snapshot} — versioned by git branch
 *       (e.g., {@code snapshot/main/}, {@code snapshot/feature/my-work/})</li>
 *   <li>{@code checkpoint} — immutable, versioned subdirectory</li>
 * </ul>
 *
 * <p>Every deployment uses a stage-and-swap strategy: SCP uploads
 * to a {@code .staging} directory, then an atomic rename replaces
 * the live directory. This eliminates stale files from previous
 * deploys (SCP alone only copies, never deletes) and avoids a
 * window where the site is missing.
 *
 * <p>By default this goal runs as a <strong>draft preview</strong>.
 * Use {@code ike:deploy-site-publish} to execute, or pass
 * {@code -Dpublish=true} explicitly.
 *
 * <p>Usage:
 * <pre>
 * mvn ike:deploy-site -DsiteType=snapshot            (preview)
 * mvn ike:deploy-site-publish -DsiteType=snapshot       (execute)
 * mvn ike:deploy-site-publish -DsiteType=release
 * mvn ike:deploy-site-publish -DsiteType=checkpoint -DsiteVersion=7-checkpoint.20260228.1
 * </pre>
 */
@Mojo(name = "deploy-site-draft", projectRequired = false, aggregator = true)
public class DeploySiteDraftMojo implements org.apache.maven.api.plugin.Mojo {

    @org.apache.maven.api.di.Inject
    private org.apache.maven.api.plugin.Log log;
    /**
     * Access the Maven logger.
     *
     * @return the logger
     */
    protected org.apache.maven.api.plugin.Log getLog() { return log; }

    private static final String SITE_URL_BASE = "scpexe://proxy/srv/ike-site/";

    @Parameter(property = "siteType")
    String siteType;

    /**
     * Explicit site version for checkpoint deploys.
     * Defaults to the POM version.
     */
    @Parameter(property = "siteVersion")
    private String siteVersion;

    /**
     * Git branch for snapshot deploys. Defaults to the current branch.
     * Used to derive the snapshot subdirectory
     * (e.g., {@code main} → {@code snapshot/main/}).
     */
    @Parameter(property = "branch")
    private String branch;

    /** Show plan without executing. */
    @Parameter(property = "publish", defaultValue = "false")
    boolean publish;

    /** Skip the {@code mvn clean verify} step. */
    @Parameter(property = "skipBuild", defaultValue = "false")
    private boolean skipBuild;

    /**
     * Skip the atomic swap (deploy directly over the live directory).
     * Not recommended — leaves stale files from previous deploys
     * and causes a brief window where the site is incomplete.
     */
    @Parameter(property = "skipSwap", defaultValue = "false")
    private boolean skipSwap;

    /**
     * Publish the rendered site to GitHub Pages on top of the internal
     * scpexe deploy. Applies only to {@code siteType=release}.
     *
     * <p>Force-pushes a single orphan commit to {@code <repo>/gh-pages}.
     * GitHub Pages then serves it at
     * {@code https://<org>.github.io/<repo>/} and — when the org has a
     * custom-domain CNAME like IKE-Network — also at
     * {@code https://<custom-domain>/<repo>/}. See ike-issues#312.
     *
     * <p>Best-effort: failure is logged but does not block the deploy.
     */
    @Parameter(property = "publishToGhPages", defaultValue = "true")
    private boolean publishToGhPages;

    /** Creates this goal instance. */
    public DeploySiteDraftMojo() {}

    @Override
    public void execute() throws MojoException {
        siteType = MojoParamSupport.requireParam(siteType, "siteType",
                "Site type (release, snapshot, or checkpoint)", getLog());

        File gitRoot = ReleaseSupport.gitRoot(new File("."));
        File mvnw = ReleaseSupport.resolveMavenWrapper(gitRoot, getLog());
        File rootPom = new File(gitRoot, "pom.xml");

        String projectId = ReleaseSupport.readPomArtifactId(rootPom);

        // Default siteVersion from POM version
        if (siteVersion == null || siteVersion.isBlank()) {
            siteVersion = ReleaseSupport.readPomVersion(rootPom);
        }

        // Default branch from git
        if (branch == null || branch.isBlank()) {
            branch = ReleaseSupport.currentBranch(gitRoot);
        }

        // Resolve target URL and disk path
        String subPath;
        String targetUrl;
        String diskPath;

        switch (siteType) {
            case "release" -> {
                // Release sites are version-prefixed (ike-issues#303);
                // siteVersion defaults to the POM version above.
                subPath = siteVersion;
                targetUrl = SITE_URL_BASE + projectId + "/" + siteVersion;
                diskPath = ReleaseSupport.siteDiskPath(
                        projectId, siteVersion, null);
            }
            case "snapshot" -> {
                String safeBranch = ReleaseSupport.branchToSitePath(branch);
                subPath = safeBranch;
                targetUrl = SITE_URL_BASE + projectId + "/snapshot/" + safeBranch;
                diskPath = ReleaseSupport.siteDiskPath(projectId, "snapshot", safeBranch);
            }
            case "checkpoint" -> {
                subPath = siteVersion;
                targetUrl = SITE_URL_BASE + projectId + "/checkpoint/" + siteVersion;
                diskPath = ReleaseSupport.siteDiskPath(projectId, "checkpoint", siteVersion);
            }
            default -> throw new MojoException(
                    "Invalid siteType: '" + siteType +
                            "'. Must be one of: release, snapshot, checkpoint");
        }

        boolean draft = !publish;

        getLog().info("");
        getLog().info("SITE DEPLOYMENT");
        getLog().info("  Project:     " + projectId);
        getLog().info("  Site type:   " + siteType);
        if ("snapshot".equals(siteType)) {
            getLog().info("  Branch:      " + branch);
        }
        if ("checkpoint".equals(siteType)) {
            getLog().info("  Version:     " + siteVersion);
        }
        getLog().info("  Target URL:  " + targetUrl);
        getLog().info("  Disk path:   " + diskPath);
        getLog().info("  Skip build:  " + skipBuild);
        getLog().info("  Skip swap:   " + skipSwap);
        getLog().info("  Publish:        "+ publish);
        getLog().info("");

        // Determine deploy URL — either staging dir or direct
        String deployUrl = skipSwap ? targetUrl
                : ReleaseSupport.siteStagingUrl(targetUrl);
        String stagingDisk = ReleaseSupport.siteStagingPath(diskPath);

        if (draft) {
            if (!skipBuild) {
                getLog().info("[DRAFT] Would run: mvnw clean verify -B");
            }
            if (!skipSwap) {
                getLog().info("[DRAFT] Would clean staging dir: " + stagingDisk);
                getLog().info("[DRAFT] Would deploy site to staging: " + deployUrl);
                getLog().info("[DRAFT] Would swap: " + stagingDisk + " → " + diskPath);
            } else {
                getLog().info("[DRAFT] Would deploy site to: " + targetUrl);
            }
            if ("release".equals(siteType)) {
                getLog().info("[DRAFT] Would update latest symlink: "
                        + "/srv/ike-site/" + projectId + "/latest -> "
                        + siteVersion);
                if (publishToGhPages) {
                    String remoteUrl = ReleaseSupport.getRemoteUrl(gitRoot, "origin");
                    if (remoteUrl != null) {
                        getLog().info("[DRAFT] Would force-push staged site "
                                + "to gh-pages on " + remoteUrl);
                        getLog().info("[DRAFT] Would publish at "
                                + "https://ike.network/" + projectId + "/");
                    } else {
                        getLog().info("[DRAFT] Would skip gh-pages publish "
                                + "(no 'origin' remote)");
                    }
                }
            }
            return;
        }

        // Build first (unless skipped)
        if (!skipBuild) {
            ReleaseSupport.exec(gitRoot, getLog(),
                    mvnw.getAbsolutePath(), "clean", "verify", "-B");
        }

        if (!skipSwap) {
            // Clean any leftover staging directory
            ReleaseSupport.cleanRemoteSiteDir(gitRoot, getLog(), stagingDisk);
        }

        // Generate, stage, and deploy site (to staging dir or direct)
        ReleaseSupport.exec(gitRoot, getLog(),
                mvnw.getAbsolutePath(), "site", "site:stage", "site:deploy", "-B",
                "-Dsite.deploy.url=" + deployUrl);

        if (!skipSwap) {
            // Atomic swap: staging → live
            ReleaseSupport.swapRemoteSiteDir(gitRoot, getLog(), diskPath);
        }

        // For release deploys, repoint the `latest` symlink so the
        // canonical URL serves this version (ike-issues#303).
        if ("release".equals(siteType)) {
            try {
                ReleaseSupport.updateLatestSymlink(gitRoot, getLog(), diskPath);
            } catch (MojoException e) {
                getLog().warn("  ⚠ latest symlink update failed (non-fatal): "
                        + e.getMessage());
            }

            // Publish to GitHub Pages — force-push the staged site to
            // <repo>/gh-pages. Best-effort: failure is logged but does
            // not block the internal scpexe deploy. ike-issues#312.
            if (publishToGhPages) {
                String remoteUrl = ReleaseSupport.getRemoteUrl(gitRoot, "origin");
                if (remoteUrl == null) {
                    getLog().info("  Skipping gh-pages publish "
                            + "(no 'origin' remote)");
                } else {
                    Path stagingDir = gitRoot.toPath()
                            .resolve("target").resolve("staging");
                    try {
                        ReleaseSupport.publishProjectSiteToGhPages(
                                stagingDir, remoteUrl, getLog(),
                                projectId, siteVersion);
                    } catch (MojoException e) {
                        getLog().warn("  ⚠ gh-pages publish failed "
                                + "(non-fatal): " + e.getMessage());
                        getLog().warn("    To retry manually: from this "
                                + "project root with 'origin' configured, "
                                + "run mvn site site:stage and copy "
                                + "target/staging/* into a fresh orphan "
                                + "branch named gh-pages, then push.");
                    }
                }
            }
        }

        String publicUrl = toPublicSiteUrl(targetUrl);
        getLog().info("");
        getLog().info("Site deployed to: " + publicUrl);
        if ("release".equals(siteType) && publishToGhPages) {
            getLog().info("GitHub Pages: https://ike.network/"
                    + projectId + "/");
        }
    }

    // ── URL conversion (pure, static, testable) ─────────────────────

    /**
     * Convert an internal SCP-style site URL to its public HTTP equivalent.
     *
     * <p>Replaces the {@code scpexe://proxy/srv/ike-site} prefix with
     * {@code http://ike.komet.sh}.
     *
     * @param scpUrl the internal deployment URL
     * @return the public-facing URL
     */
    public static String toPublicSiteUrl(String scpUrl) {
        return scpUrl.replace("scpexe://proxy/srv/ike-site", "http://ike.komet.sh");
    }
}
