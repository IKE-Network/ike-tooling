package network.ike.plugin;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Full release: build, deploy, tag, merge, and bump to next SNAPSHOT.
 *
 * <p>This goal automates the complete release workflow in one command.
 * All local git work completes before any external action, so a
 * deploy failure leaves the local repository in a consistent state
 * and the deploy can be retried manually.
 *
 * <p><strong>Local phase (idempotent):</strong></p>
 * <ol>
 *   <li>Validate prerequisites (branch, clean worktree)</li>
 *   <li>Create {@code release/<version>} branch</li>
 *   <li>Set POM version to release version</li>
 *   <li>Build and verify</li>
 *   <li>Build site (pre-flight — catches javadoc errors early)</li>
 *   <li>Commit, tag</li>
 *   <li>Restore {@code ${project.version}}, merge to main</li>
 *   <li>Bump to next SNAPSHOT version, verify, commit</li>
 * </ol>
 *
 * <p><strong>External phase (most reversible first, irreversible last):</strong></p>
 * <ol>
 *   <li>Deploy site from tagged commit (overwritable — safe to retry)</li>
 *   <li>Deploy to Nexus from tagged commit (irreversible — last)</li>
 *   <li>Push tag and main to origin</li>
 *   <li>Create GitHub Release</li>
 * </ol>
 *
 * <p>By default this goal runs as a <strong>dry-run preview</strong>.
 * Use {@code ike:release-apply} to execute the release, or pass
 * {@code -DdryRun=false} explicitly.
 *
 * <p>Usage: {@code mvn ike:release} (preview),
 * {@code mvn ike:release-apply} (execute),
 * or override version with {@code mvn ike:release-apply -DreleaseVersion=2}
 *
 */
@Mojo(name = "release", requiresProject = false, aggregator = true, threadSafe = true)
public class ReleaseMojo extends AbstractMojo {

    @Parameter(property = "releaseVersion")
    String releaseVersion;

    @Parameter(property = "nextVersion")
    String nextVersion;

    @Parameter(property = "dryRun", defaultValue = "true")
    boolean dryRun;

    @Parameter(property = "skipVerify", defaultValue = "false")
    boolean skipVerify;

    @Parameter(property = "allowBranch")
    String allowBranch;

    @Parameter(property = "deploySite", defaultValue = "true")
    boolean deploySite;

    /**
     * Publish the site to GitHub Pages after internal site deploy.
     * Uses {@code ike:publish-site} to force-push an orphan commit
     * to the {@code gh-pages} branch.
     */
    @Parameter(property = "publishSite", defaultValue = "true")
    boolean publishSite;

    /**
     * GitHub repository for issue tracking, used to look up a milestone
     * named {@code <artifactId> v<version>} for release notes generation.
     * If the milestone exists, its closed issues are formatted as the
     * GitHub Release body. Falls back to {@code --generate-notes} if
     * no milestone is found.
     */
    @Parameter(property = "issueRepo", defaultValue = "IKE-Network/ike-issues")
    String issueRepo;

    /** Override working directory for tests. If null, uses current directory. */
    File baseDir;

    /** Creates this goal instance. */
    public ReleaseMojo() {}

    @Override
    public void execute() throws MojoExecutionException {
        File startDir = baseDir != null ? baseDir : new File(".");
        File gitRoot = ReleaseSupport.gitRoot(startDir);
        File rootPom = new File(gitRoot, "pom.xml");

        // Default releaseVersion from current POM version
        String oldVersion = ReleaseSupport.readPomVersion(rootPom);
        if (releaseVersion == null || releaseVersion.isBlank()) {
            releaseVersion = ReleaseSupport.deriveReleaseVersion(oldVersion);
            getLog().info("No -DreleaseVersion specified; defaulting to: " + releaseVersion);
        }

        // Default nextVersion
        if (nextVersion == null || nextVersion.isBlank()) {
            nextVersion = ReleaseSupport.deriveNextSnapshot(releaseVersion);
        }

        // Reject SNAPSHOT release versions
        if (releaseVersion.contains("-SNAPSHOT")) {
            throw new MojoExecutionException(
                    "Release version must not contain -SNAPSHOT.");
        }

        // Enforce SNAPSHOT suffix on next version
        if (!nextVersion.endsWith("-SNAPSHOT")) {
            throw new MojoExecutionException(
                    "Next version must end with -SNAPSHOT (got '" + nextVersion + "').");
        }

        // Validate branch
        String currentBranch = ReleaseSupport.currentBranch(gitRoot);
        String expectedBranch = allowBranch != null ? allowBranch : "main";
        if (!currentBranch.equals(expectedBranch)) {
            throw new MojoExecutionException(
                    "Must be on '" + expectedBranch + "' branch (currently on '" +
                            currentBranch + "'). Use -DallowBranch=" +
                            currentBranch + " to override.");
        }

        // Check release branch doesn't already exist
        String releaseBranch = "release/" + releaseVersion;
        try {
            ReleaseSupport.execCapture(gitRoot,
                    "git", "rev-parse", "--verify", releaseBranch);
            throw new MojoExecutionException(
                    "Branch '" + releaseBranch + "' already exists locally.");
        } catch (MojoExecutionException e) {
            if (e.getMessage().startsWith("Branch '")) throw e;
            // Expected — branch does not exist
        }

        String projectId = ReleaseSupport.readPomArtifactId(rootPom);

        // Validate clean worktree (cheap check — before wrapper resolution)
        ReleaseSupport.requireCleanWorktree(gitRoot);

        // Derive timestamp from the current HEAD commit, not wall-clock time.
        // This ensures two independent builds from the same tag produce the
        // same project.build.outputTimestamp value — which is the reproducibility
        // guarantee. Wall-clock time would defeat the purpose.
        String releaseTimestamp = resolveCommitTimestamp(gitRoot);

        if (dryRun) {
            getLog().info("[DRY RUN] Would create branch: " + releaseBranch);
            getLog().info("[DRY RUN] Would set version: " + oldVersion +
                    " -> " + releaseVersion);
            getLog().info("[DRY RUN] Would stamp project.build.outputTimestamp: "
                    + releaseTimestamp);
            getLog().info("[DRY RUN] Would resolve ${project.version} -> " +
                    releaseVersion + " in all POMs");
            getLog().info("[DRY RUN] Would run: mvnw clean verify -B");
            getLog().info("[DRY RUN] Would commit, tag v" + releaseVersion);
            getLog().info("[DRY RUN] Would restore ${project.version} references");
            getLog().info("[DRY RUN] Would merge " + releaseBranch + " to main");
            getLog().info("[DRY RUN] Would bump to next version: " + nextVersion);
            getLog().info("[DRY RUN] --- all local work above, external below ---");
            if (deploySite) {
                getLog().info("[DRY RUN] Would generate site (must succeed)");
            }
            getLog().info("[DRY RUN] Would deploy to Nexus from tag v" +
                    releaseVersion + " (critical)");
            if (deploySite) {
                getLog().info("[DRY RUN] Would deploy site to: " +
                        "scpexe://proxy/srv/ike-site/" + projectId + "/release"
                        + " (best-effort)");
            }
            if (publishSite && deploySite) {
                getLog().info("[DRY RUN] Would publish site to GitHub Pages (best-effort)");
            }
            getLog().info("[DRY RUN] Would push tag and main to origin");
            getLog().info("[DRY RUN] Would create GitHub Release");
            return;
        }

        // ── Release ───────────────────────────────────────────────────

        // Resolve Maven wrapper (requires mvnw or mvn on PATH — skip for dry-run)
        File mvnw = ReleaseSupport.resolveMavenWrapper(gitRoot, getLog());

        // Build environment audit (needs mvnw for --version)
        logAudit(gitRoot, mvnw, currentBranch, releaseBranch, oldVersion, projectId);

        // Create release branch
        ReleaseSupport.exec(gitRoot, getLog(),
                "git", "checkout", "-b", releaseBranch);

        // Set version
        getLog().info("Setting version: " + oldVersion + " -> " + releaseVersion);
        ReleaseSupport.setPomVersion(rootPom, oldVersion, releaseVersion);

        // Stamp reproducible build timestamp
        getLog().info("Stamping project.build.outputTimestamp: " + releaseTimestamp);
        ReleaseSupport.stampOutputTimestamp(rootPom, releaseTimestamp, getLog());

        // WORKAROUND: Maven 4 consumer POM doesn't resolve ${project.version}
        // in <build><plugins>, <pluginManagement>, or <dependencyManagement>.
        getLog().info("Resolving ${project.version} references:");
        List<File> resolvedPoms =
                ReleaseSupport.replaceProjectVersionRefs(gitRoot, releaseVersion, getLog());

        // Build and install (not just verify) — reactor siblings with
        // BOM imports need installed artifacts to resolve classified
        // dependencies (e.g., ike-build-standards:zip:claude). The
        // release version has never been installed, so 'verify' alone
        // fails on inter-module resolution. Using 'install' puts
        // artifacts in the local repo for sibling resolution.
        if (!skipVerify) {
            ReleaseSupport.exec(gitRoot, getLog(),
                    mvnw.getAbsolutePath(), "clean", "install", "-B", "-T", "1");
        } else {
            getLog().info("Skipping verify (-DskipVerify=true)");
        }

        // Build site (catches javadoc errors before any commits/tags).
        // -T 1 overrides .mvn/maven.config parallelism: maven-site-plugin
        // is not @ThreadSafe and emits a warning in parallel sessions.
        if (deploySite) {
            getLog().info("Building site (pre-flight check)...");
            ReleaseSupport.exec(gitRoot, getLog(),
                    mvnw.getAbsolutePath(), "site", "site:stage", "-B", "-T", "1");
        }

        // Commit
        ReleaseSupport.exec(gitRoot, getLog(), "git", "add", "pom.xml");
        ReleaseSupport.gitAddFiles(gitRoot, getLog(), resolvedPoms);
        ReleaseSupport.exec(gitRoot, getLog(),
                "git", "commit", "-m",
                "release: set version to " + releaseVersion);

        // Tag
        ReleaseSupport.exec(gitRoot, getLog(),
                "git", "tag", "-a", "v" + releaseVersion,
                "-m", "Release " + releaseVersion);

        // Restore ${project.version} references
        getLog().info("Restoring ${project.version} references:");
        List<File> restoredPoms = ReleaseSupport.restoreBackups(gitRoot, getLog());
        if (!restoredPoms.isEmpty()) {
            ReleaseSupport.gitAddFiles(gitRoot, getLog(), restoredPoms);
            ReleaseSupport.exec(gitRoot, getLog(),
                    "git", "commit", "-m",
                    "release: restore ${project.version} references");
        }

        // Merge back to main
        ReleaseSupport.exec(gitRoot, getLog(), "git", "checkout", "main");
        ReleaseSupport.exec(gitRoot, getLog(),
                "git", "merge", "--no-ff", releaseBranch,
                "-m", "merge: release " + releaseVersion);

        // ── Post-release bump ─────────────────────────────────────────

        getLog().info("");
        getLog().info("Bumping to next version: " + nextVersion);

        // Re-read version after merge (it's the release version on main now)
        String currentVersion = ReleaseSupport.readPomVersion(rootPom);
        ReleaseSupport.setPomVersion(rootPom, currentVersion, nextVersion);

        // Verify build with new SNAPSHOT version
        ReleaseSupport.exec(gitRoot, getLog(),
                mvnw.getAbsolutePath(), "clean", "verify", "-B", "-T", "1");

        // Commit
        ReleaseSupport.exec(gitRoot, getLog(), "git", "add", "pom.xml");
        ReleaseSupport.exec(gitRoot, getLog(),
                "git", "commit", "-m",
                "post-release: bump to " + nextVersion);

        // Clean up release branch
        ReleaseSupport.exec(gitRoot, getLog(),
                "git", "branch", "-d", releaseBranch);

        // ── External actions (all local work is done) ─────────────────
        // Everything above this point is local and idempotent. If any
        // external action below fails, all local git state is consistent
        // and the deploy can be retried manually.
        //
        // Order: generate site, deploy site, then Nexus (clean build):
        //   1. Generate site (verify → site → stage — catches errors early)
        //   2. Site deploy + publish (best-effort, while target/staging/ exists)
        //   3. Nexus deploy (clean deploy — fresh build for artifact integrity)
        //   4. Push tag + main (additive — safe to retry)
        //   5. GitHub Release (additive — safe to retry)

        getLog().info("");
        getLog().info("Local work complete. Starting external deploys...");
        getLog().info("");

        boolean hasOrigin = ReleaseSupport.hasRemote(gitRoot, "origin");

        // Deploy from the tagged release commit
        ReleaseSupport.exec(gitRoot, getLog(),
                "git", "checkout", "v" + releaseVersion);

        // Site URL info needed for both generation and deploy phases
        String releaseDisk = null;
        String stagingUrl = null;
        try {
            // ── Site generation (must succeed before Nexus deploy) ────
            // A release without a valid site is incomplete. The tag
            // checkout wiped target/, so everything is rebuilt here.
            if (deploySite) {
                releaseDisk = ReleaseSupport.siteDiskPath(
                        projectId, "release", null);
                String stagingDisk = ReleaseSupport.siteStagingPath(releaseDisk);
                String releaseUrl = "scpexe://proxy" + releaseDisk;
                stagingUrl = ReleaseSupport.siteStagingUrl(releaseUrl);

                // 1. Verify (generates JaCoCo coverage data)
                ReleaseSupport.exec(gitRoot, getLog(),
                        mvnw.getAbsolutePath(), "verify", "-B", "-T", "1");

                // 2. Generate release history XHTML for site inclusion
                try {
                    Path generatedXhtml = gitRoot.toPath()
                            .resolve("target").resolve("generated-site").resolve("xhtml");
                    Path xhtmlFile = ReleaseNotesSupport.generateFullHistoryXhtml(
                            issueRepo, generatedXhtml, getLog());
                    if (xhtmlFile != null) {
                        getLog().info("Generated release history: " + xhtmlFile);
                    }
                } catch (MojoExecutionException e) {
                    getLog().warn("Could not generate site release notes: "
                            + e.getMessage());
                }

                // 3. Build site (generates JaCoCo HTML from jacoco.exec)
                ReleaseSupport.exec(gitRoot, getLog(),
                        mvnw.getAbsolutePath(), "site", "-B", "-T", "1");

                // 4. Inject breadcrumbs into JaCoCo reports
                getLog().info("Injecting breadcrumbs into JaCoCo reports...");
                ReleaseSupport.exec(gitRoot, getLog(),
                        mvnw.getAbsolutePath(), "network.ike.tooling:ike-maven-plugin:inject-breadcrumb",
                        "-B", "-T", "1");

                // 5. Stage site (packages for deploy)
                ReleaseSupport.exec(gitRoot, getLog(),
                        mvnw.getAbsolutePath(), "site:stage", "-B", "-T", "1");

                // 6. Deploy site + publish (while target/staging/ exists)
                // Best-effort — failures warn but don't block Nexus deploy.
                try {
                    deploySiteAndPublish(gitRoot, mvnw, projectId,
                            releaseVersion, releaseDisk, stagingUrl);
                } catch (MojoExecutionException e) {
                    logSiteDeployRetryInstructions(projectId, releaseVersion,
                            e.getMessage());
                }
            }

            // ── Nexus deploy (critical — the actual release) ─────────
            // clean deploy: fresh build ensures artifact integrity.
            // Site was already deployed above (before clean wipes staging).
            getLog().info("Deploying to Nexus...");
            ReleaseSupport.exec(gitRoot, getLog(),
                    mvnw.getAbsolutePath(), "clean", "deploy", "-B", "-T", "1",
                    "-P", "release,signArtifacts");
        } finally {
            // Always return to main, even if deploy fails
            ReleaseSupport.exec(gitRoot, getLog(), "git", "checkout", "main");
        }

        // Push tag and main
        if (hasOrigin) {
            ReleaseSupport.exec(gitRoot, getLog(),
                    "git", "push", "origin", "v" + releaseVersion);
            ReleaseSupport.exec(gitRoot, getLog(),
                    "git", "push", "origin", "main");
        } else {
            getLog().info("No 'origin' remote — skipping push");
        }

        // Create GitHub Release with milestone-based release notes
        if (hasOrigin) {
            createGitHubRelease(gitRoot, projectId, releaseVersion);
        } else {
            getLog().info("No 'origin' remote — skipping GitHub Release");
        }

        // Clean up the main-branch snapshot site — the release site
        // replaces it. Non-fatal if it fails (may not exist).
        if (deploySite) {
            String snapshotDisk = ReleaseSupport.siteDiskPath(
                    projectId, "snapshot", "main");
            try {
                getLog().info("Cleaning snapshot/main site...");
                ReleaseSupport.cleanRemoteSiteDir(gitRoot, getLog(), snapshotDisk);
            } catch (MojoExecutionException e) {
                getLog().warn("Could not clean snapshot site (may not exist): "
                        + e.getMessage());
            }
        }

        // VCS state file now managed by ws:release for workspace-level
        // releases. Single-repo ike:release does not write VCS state.

        getLog().info("");
        getLog().info("Release " + releaseVersion + " complete.");
        getLog().info("  Tagged: v" + releaseVersion);
        getLog().info("  Deployed to Nexus");
        if (deploySite) {
            getLog().info("  Site: http://ike.komet.sh/" + projectId + "/release/");
        }
        if (publishSite && deploySite) {
            getLog().info("  GitHub Pages: https://ike.network/" + projectId + "/");
        }
        getLog().info("  Merged to main");
        getLog().info("  Next version: " + nextVersion);
    }

    /**
     * Return the ISO-8601 UTC timestamp of the current HEAD commit.
     *
     * <p>Using the commit timestamp (not wall-clock time) for
     * {@code project.build.outputTimestamp} ensures that two independent
     * builds from the same tag produce identical byte-for-byte output.
     * Wall-clock time would differ between the developer build and the
     * TeamCity verification build, defeating reproducibility.
     *
     * <p>Falls back to the current wall-clock time if git is unavailable.
     */
    private String resolveCommitTimestamp(File gitRoot) {
        try {
            // %cI = commit timestamp in strict ISO 8601 format
            String raw = ReleaseSupport.execCapture(gitRoot,
                    "git", "log", "-1", "--format=%cI", "HEAD");
            // Normalise to the yyyy-MM-dd'T'HH:mm:ss'Z' form Maven expects
            return DateTimeFormatter
                    .ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
                    .withZone(ZoneOffset.UTC)
                    .format(java.time.OffsetDateTime.parse(raw).toInstant());
        } catch (Exception e) {
            getLog().warn("Could not read HEAD commit timestamp; falling back to wall-clock: "
                    + e.getMessage());
            return DateTimeFormatter
                    .ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
                    .withZone(ZoneOffset.UTC)
                    .format(Instant.now());
        }
    }

    private void logAudit(File gitRoot, File mvnw, String branch,
                          String releaseBranch, String oldVersion,
                          String projectId) throws MojoExecutionException {
        String gitCommit = ReleaseSupport.execCapture(gitRoot,
                "git", "rev-parse", "--short", "HEAD");
        String mavenVersion = ReleaseSupport.execCapture(gitRoot,
                mvnw.getAbsolutePath(), "--version");
        String javaVersion = System.getProperty("java.version", "unknown");

        getLog().info("");
        getLog().info("RELEASE PARAMETERS");
        getLog().info("  Version:        " + oldVersion + " -> " + releaseVersion);
        getLog().info("  Next version:   " + nextVersion);
        getLog().info("  Source branch:  " + branch);
        getLog().info("  Release branch: " + releaseBranch);
        getLog().info("  Tag:            v" + releaseVersion);
        getLog().info("  Project:        " + projectId);
        getLog().info("  Deploy site:    " + deploySite);
        getLog().info("  Publish site:   " + publishSite);
        getLog().info("  Skip verify:    " + skipVerify);
        getLog().info("  Dry run:        " + dryRun);
        getLog().info("");
        getLog().info("BUILD ENVIRONMENT");
        getLog().info("  Date:           " + Instant.now());
        getLog().info("  User:           " + System.getProperty("user.name", "unknown"));
        getLog().info("  Git commit:     " + gitCommit);
        getLog().info("  Git root:       " + gitRoot.getAbsolutePath());
        getLog().info("  Maven:          " + mavenVersion.lines().findFirst().orElse("unknown"));
        getLog().info("  Java version:   " + javaVersion);
        getLog().info("  OS:             " + System.getProperty("os.name") + " " +
                System.getProperty("os.arch"));
        getLog().info("");
    }

    /**
     * Deploy the staged site and publish to GitHub Pages.
     *
     * <p>Called only after site generation and Nexus deploy have both
     * succeeded. Failures here are caught by the caller and reported
     * as warnings with retry instructions.
     */
    private void deploySiteAndPublish(File gitRoot, File mvnw,
                                       String projectId, String version,
                                       String releaseDisk, String stagingUrl)
            throws MojoExecutionException {
        String stagingDisk = ReleaseSupport.siteStagingPath(releaseDisk);

        getLog().info("Deploying site to staging...");
        ReleaseSupport.cleanRemoteSiteDir(gitRoot, getLog(), stagingDisk);
        ReleaseSupport.exec(gitRoot, getLog(),
                mvnw.getAbsolutePath(), "site:deploy", "-B", "-T", "1",
                "-Dsite.deploy.url=" + stagingUrl);
        ReleaseSupport.swapRemoteSiteDir(gitRoot, getLog(), releaseDisk);

        if (publishSite) {
            getLog().info("Publishing site to GitHub Pages...");
            ReleaseSupport.exec(gitRoot, getLog(),
                    mvnw.getAbsolutePath(), "network.ike.tooling:ike-maven-plugin:publish-site", "-B",
                    "-DpublishMessage=site: publish " + projectId
                            + " " + version);
        }
    }

    /**
     * Log retry instructions when site deploy/publish fails after a
     * successful Nexus deploy. Keeps the release from failing.
     */
    private void logSiteDeployRetryInstructions(String projectId,
                                                 String version,
                                                 String errorMessage) {
        getLog().warn("");
        getLog().warn("Site deploy/publish failed: " + errorMessage);
        getLog().warn("Nexus deploy succeeded — artifacts are published.");
        getLog().warn("To retry site deploy manually:");
        getLog().warn("  git checkout v" + version);
        getLog().warn("  mvn site:deploy -B -T 1");
        getLog().warn("  mvn ike:publish-site -B"
                + " -DpublishMessage=\"site: publish " + projectId
                + " " + version + "\"");
        getLog().warn("  git checkout main");
        getLog().warn("");
    }

    /**
     * Create a GitHub Release with milestone-based release notes.
     *
     * <p>Looks for a milestone named {@code <projectId> v<version>}
     * in the configured issue repository. If found, generates formatted
     * release notes from its closed issues. Falls back to GitHub's
     * auto-generated commit-based notes if no milestone exists.
     */
    private void createGitHubRelease(File gitRoot, String projectId,
                                      String version)
            throws MojoExecutionException {
        String milestoneName = projectId + " v" + version;

        // Try milestone-based notes first
        Path notesFile = ReleaseNotesSupport.generateToFile(
                issueRepo, milestoneName, getLog());

        try {
            if (notesFile != null) {
                getLog().info("Release notes generated from milestone: "
                        + milestoneName);
                ReleaseSupport.exec(gitRoot, getLog(),
                        "gh", "release", "create", "v" + version,
                        "--title", version,
                        "--notes-file", notesFile.toString(),
                        "--verify-tag");
            } else {
                getLog().info("No milestone \"" + milestoneName
                        + "\" found — using auto-generated notes");
                ReleaseSupport.exec(gitRoot, getLog(),
                        "gh", "release", "create", "v" + version,
                        "--title", version,
                        "--generate-notes", "--verify-tag");
            }
        } catch (MojoExecutionException e) {
            getLog().warn("GitHub Release creation failed "
                    + "(gh CLI may not be installed): " + e.getMessage());
            getLog().warn("Run manually: gh release create v" + version
                    + " --title " + version + " --generate-notes");
        }

        // Close the milestone now that the release has shipped.
        // Non-fatal — the release is already done at this point.
        if (notesFile != null) {
            try {
                ReleaseNotesSupport.closeMilestone(issueRepo, milestoneName, getLog());
            } catch (MojoExecutionException e) {
                getLog().warn("Could not close milestone (release succeeded): "
                        + e.getMessage());
                getLog().warn("Close manually: gh api repos/" + issueRepo
                        + "/milestones/1 -X PATCH -f state=closed");
            }
        }
    }
}
