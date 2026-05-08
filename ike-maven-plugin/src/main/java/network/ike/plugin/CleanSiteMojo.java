package network.ike.plugin;

import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.File;

/**
 * Remove a deployed site directory from the server.
 *
 * <p>Useful for manual cleanup of stale snapshot or checkpoint sites
 * that were not automatically removed by {@code feature-finish} or
 * {@code release}.
 *
 * <p>Usage:
 * <pre>
 * mvn ike:clean-site -DsiteType=snapshot -Dbranch=feature/old-work
 * mvn ike:clean-site -DsiteType=checkpoint -DsiteVersion=7-checkpoint.20260101.1
 * mvn ike:clean-site -DsiteType=snapshot              # cleans current branch's snapshot
 * </pre>
 */
@Mojo(name = "clean-site", projectRequired = false)
public class CleanSiteMojo implements org.apache.maven.api.plugin.Mojo {

    @org.apache.maven.api.di.Inject
    private org.apache.maven.api.plugin.Log log;
    /**
     * Access the Maven logger.
     *
     * @return the logger
     */
    protected org.apache.maven.api.plugin.Log getLog() { return log; }

    /**
     * Site type to clean: snapshot, checkpoint, or release.
     */
    @Parameter(property = "siteType", required = true)
    private String siteType;

    /**
     * Branch whose snapshot site should be cleaned.
     * Defaults to the current git branch.
     * Only used when siteType=snapshot.
     */
    @Parameter(property = "branch")
    private String branch;

    /**
     * Version of the checkpoint or release site to clean.
     * Only used when siteType=checkpoint or siteType=release.
     * For {@code siteType=release}, defaults to the POM version
     * if omitted.
     */
    @Parameter(property = "siteVersion")
    private String siteVersion;

    /** Show plan without executing. */
    @Parameter(property = "publish", defaultValue = "true")
    private boolean publish;

    /** Creates this goal instance. */
    public CleanSiteMojo() {}

    @Override
    public void execute() throws MojoException {
        File gitRoot = ReleaseSupport.gitRoot(new File("."));
        File rootPom = new File(gitRoot, "pom.xml");
        String projectId = ReleaseSupport.readPomArtifactId(rootPom);

        String diskPath;

        switch (siteType) {
            case "release" -> {
                // Release sites are version-prefixed (ike-issues#303).
                // Default to POM version when siteVersion is omitted.
                if (siteVersion == null || siteVersion.isBlank()) {
                    siteVersion = ReleaseSupport.readPomVersion(rootPom);
                }
                diskPath = ReleaseSupport.siteDiskPath(
                        projectId, siteVersion, null);
            }
            case "snapshot" -> {
                if (branch == null || branch.isBlank()) {
                    branch = ReleaseSupport.currentBranch(gitRoot);
                }
                String safeBranch = ReleaseSupport.branchToSitePath(branch);
                diskPath = ReleaseSupport.siteDiskPath(
                        projectId, "snapshot", safeBranch);
            }
            case "checkpoint" -> {
                if (siteVersion == null || siteVersion.isBlank()) {
                    throw new MojoException(
                            "siteVersion is required for checkpoint cleanup. "
                                    + "Specify -DsiteVersion=<version>.");
                }
                diskPath = ReleaseSupport.siteDiskPath(
                        projectId, "checkpoint", siteVersion);
            }
            default -> throw new MojoException(
                    "Invalid siteType: '" + siteType
                            + "'. Must be one of: release, snapshot, checkpoint");
        }

        boolean draft = !publish;

        getLog().info("");
        getLog().info("SITE CLEANUP");
        getLog().info("  Project:   " + projectId);
        getLog().info("  Type:      " + siteType);
        if ("snapshot".equals(siteType)) {
            getLog().info("  Branch:    " + branch);
        }
        if ("checkpoint".equals(siteType) || "release".equals(siteType)) {
            getLog().info("  Version:   " + siteVersion);
        }
        getLog().info("  Disk path: " + diskPath);
        getLog().info("");

        if (draft) {
            getLog().info("[DRAFT] Would remove: " + diskPath);
            return;
        }

        ReleaseSupport.cleanRemoteSiteDir(gitRoot, getLog(), diskPath);
        getLog().info("Cleaned: " + diskPath);
    }
}
