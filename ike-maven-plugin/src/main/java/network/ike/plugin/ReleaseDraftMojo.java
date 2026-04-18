package network.ike.plugin;

import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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
 * <p>By default this goal runs as a <strong>draft preview</strong>.
 * Use {@code ike:release-publish} to execute the release, or pass
 * {@code -Dpublish=true} explicitly.
 *
 * <p>Usage: {@code mvn ike:release} (preview),
 * {@code mvn ike:release-publish} (execute),
 * or override version with {@code mvn ike:release-publish -DreleaseVersion=2}
 *
 */
@Mojo(name = "release-draft", projectRequired = false, aggregator = true)
public class ReleaseDraftMojo extends AbstractIkeMojo {

    @Parameter(property = "releaseVersion")
    String releaseVersion;

    @Parameter(property = "nextVersion")
    String nextVersion;

    @Parameter(property = "publish", defaultValue = "false")
    boolean publish;

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
    public ReleaseDraftMojo() {}

    @Override
    public void execute() throws MojoException {
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
            throw new MojoException(
                    "Release version must not contain -SNAPSHOT.");
        }

        // Enforce SNAPSHOT suffix on next version
        if (!nextVersion.endsWith("-SNAPSHOT")) {
            throw new MojoException(
                    "Next version must end with -SNAPSHOT (got '" + nextVersion + "').");
        }

        // Validate branch and detect resume scenario (#111)
        String currentBranch = ReleaseSupport.currentBranch(gitRoot);
        String releaseBranch = "release/" + releaseVersion;
        boolean resuming = false;

        if (currentBranch.equals(releaseBranch)) {
            // Already on the release branch — resume from a failed attempt
            getLog().info("Resuming release from existing " + releaseBranch + " branch");
            resuming = true;
        } else {
            String expectedBranch = allowBranch != null ? allowBranch : "main";
            if (!currentBranch.equals(expectedBranch)) {
                throw new MojoException(
                        "Must be on '" + expectedBranch + "' branch (currently on '" +
                                currentBranch + "'). Use -DallowBranch=" +
                                currentBranch + " to override.");
            }

            // Check release branch doesn't already exist
            boolean releaseBranchExists = false;
            try {
                ReleaseSupport.execCapture(gitRoot,
                        "git", "rev-parse", "--verify", releaseBranch);
                releaseBranchExists = true;
            } catch (Exception e) {
                // Expected — branch does not exist
            }
            if (releaseBranchExists) {
                throw new MojoException(
                        "Branch '" + releaseBranch + "' already exists locally. "
                        + "Switch to it to resume, or delete it to start fresh:\n"
                        + "  Resume: git checkout " + releaseBranch
                        + " && mvn ike:release-publish\n"
                        + "  Fresh:  git branch -D " + releaseBranch
                        + " && mvn ike:release-publish");
            }
        }

        String projectId = ReleaseSupport.readPomArtifactId(rootPom);
        boolean draft = !publish;

        // Validate clean worktree (cheap check — before wrapper resolution)
        ReleaseSupport.requireCleanWorktree(gitRoot);

        // ── Preflight: verify external connectivity before any work ──
        // Every external action is checked upfront so failures happen
        // in seconds, not after a 10-minute build. Each check is
        // non-destructive and idempotent.
        boolean hasOrigin = ReleaseSupport.hasRemote(gitRoot, "origin");
        if (publish) {
            preflightChecks(gitRoot, hasOrigin, projectId);
        }
        // Javadoc preflight (#168) — runs in both modes. Publish fails
        // on warnings; draft logs them so the user sees what would block
        // the real release.
        preflightJavadoc(gitRoot, publish);

        // Derive timestamp from the current HEAD commit, not wall-clock time.
        // This ensures two independent builds from the same tag produce the
        // same project.build.outputTimestamp value — which is the reproducibility
        // guarantee. Wall-clock time would defeat the purpose.
        String releaseTimestamp = resolveCommitTimestamp(gitRoot);

        if (draft) {
            getLog().info("[DRAFT] Would create branch: " + releaseBranch);
            getLog().info("[DRAFT] Would set version: " + oldVersion +
                    " -> " + releaseVersion);
            getLog().info("[DRAFT] Would stamp project.build.outputTimestamp: "
                    + releaseTimestamp);
            getLog().info("[DRAFT] Would resolve ${project.version} -> " +
                    releaseVersion + " in all POMs");
            getLog().info("[DRAFT] Would run: mvnw clean verify -B");
            getLog().info("[DRAFT] Would commit, tag v" + releaseVersion);
            getLog().info("[DRAFT] Would restore ${project.version} references");
            getLog().info("[DRAFT] Would merge " + releaseBranch + " to main");
            getLog().info("[DRAFT] Would bump to next version: " + nextVersion);
            getLog().info("[DRAFT] --- all local work above, external below ---");
            if (deploySite) {
                getLog().info("[DRAFT] Would generate site (must succeed)");
            }
            getLog().info("[DRAFT] Would deploy to Nexus from tag v" +
                    releaseVersion + " (critical)");
            if (deploySite) {
                getLog().info("[DRAFT] Would deploy site to: " +
                        "scpexe://proxy/srv/ike-site/" + projectId + "/release"
                        + " (best-effort)");
            }
            getLog().info("[DRAFT] Would push tag and main to origin");
            getLog().info("[DRAFT] Would create GitHub Release");
            writeReport(IkeGoal.RELEASE_DRAFT, startDir.toPath(),
                    buildReleaseReport(true, oldVersion, releaseBranch,
                            projectId, releaseTimestamp));
            return;
        }

        // ── Release ───────────────────────────────────────────────────

        // Resolve Maven wrapper (requires mvnw or mvn on PATH — skip for draft)
        File mvnw = ReleaseSupport.resolveMavenWrapper(gitRoot, getLog());

        // Build environment audit (needs mvnw for --version)
        logAudit(gitRoot, mvnw, currentBranch, releaseBranch, oldVersion, projectId);

        List<File> resolvedPoms;
        if (resuming) {
            // Skip branch creation and version setting — already done
            getLog().info("Skipping version set (already " + releaseVersion + ")");
            resolvedPoms = List.of(); // backups handle restore later
        } else {
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
            resolvedPoms =
                    ReleaseSupport.replaceProjectVersionRefs(gitRoot, releaseVersion, getLog());
        }

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
                } catch (Exception e) {
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
                } catch (Exception e) {
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
            } catch (Exception e) {
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

        writeReport(IkeGoal.RELEASE_PUBLISH, startDir.toPath(),
                buildReleaseReport(false, oldVersion, releaseBranch,
                        projectId, releaseTimestamp));
    }

    /**
     * Build the markdown body for an {@code ike:release-*} session report.
     *
     * @param draft            {@code true} for draft preview, {@code false}
     *                         for a completed publish run
     * @param oldVersion       the pre-release POM version
     * @param releaseBranch    the release branch that was (or would be) created
     * @param projectId        the artifactId of the project being released
     * @param releaseTimestamp the reproducible build timestamp stamped
     *                         into {@code project.build.outputTimestamp}
     * @return the markdown body
     */
    private String buildReleaseReport(boolean draft, String oldVersion,
                                       String releaseBranch,
                                       String projectId,
                                       String releaseTimestamp) {
        StringBuilder sb = new StringBuilder();
        sb.append("**Project:** ").append(projectId).append("\n");
        sb.append("**Mode:** ").append(draft ? "draft (preview)" : "publish")
                .append("\n");
        sb.append("**Version:** ").append(oldVersion).append(" → ")
                .append(releaseVersion).append("\n");
        sb.append("**Next version:** ").append(nextVersion).append("\n");
        sb.append("**Release branch:** ").append(releaseBranch).append("\n");
        sb.append("**Tag:** v").append(releaseVersion).append("\n");
        sb.append("**Timestamp:** ").append(releaseTimestamp).append("\n\n");

        String verb = draft ? "Would" : "Did";
        sb.append("## Local actions\n");
        sb.append("1. ").append(verb)
                .append(" create branch `").append(releaseBranch).append("`\n");
        sb.append("2. ").append(verb)
                .append(" set version ").append(oldVersion).append(" → ")
                .append(releaseVersion).append("\n");
        sb.append("3. ").append(verb)
                .append(" stamp `project.build.outputTimestamp`\n");
        sb.append("4. ").append(verb)
                .append(" resolve `${project.version}` in all POMs\n");
        sb.append("5. ").append(verb).append(" run `mvnw clean verify -B`\n");
        sb.append("6. ").append(verb)
                .append(" commit and tag `v").append(releaseVersion)
                .append("`\n");
        sb.append("7. ").append(verb)
                .append(" merge `").append(releaseBranch).append("` to main\n");
        sb.append("8. ").append(verb)
                .append(" bump to next version ").append(nextVersion)
                .append("\n\n");

        sb.append("## External actions\n");
        int step = 1;
        if (deploySite) {
            sb.append(step++).append(". ").append(verb)
                    .append(" generate site\n");
        }
        sb.append(step++).append(". ").append(verb)
                .append(" deploy to Nexus from tag `v")
                .append(releaseVersion).append("`\n");
        if (deploySite) {
            sb.append(step++).append(". ").append(verb)
                    .append(" deploy site to ")
                    .append("`scpexe://proxy/srv/ike-site/")
                    .append(projectId).append("/release`\n");
        }
        sb.append(step++).append(". ").append(verb)
                .append(" push tag and main to origin\n");
        sb.append(step).append(". ").append(verb)
                .append(" create GitHub Release\n");

        return sb.toString();
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

    /**
     * Verify all external dependencies before starting the release.
     *
     * <p>Each check is non-destructive and fast. Failures here happen
     * in seconds instead of after a 10-minute build cycle.
     *
     * <p>Checks:
     * <ol>
     *   <li>Git push — can we authenticate to the remote?</li>
     *   <li>SSH proxy — can we reach the site deploy server?</li>
     *   <li>gh CLI — is it installed and authenticated?</li>
     *   <li>Maven wrapper — is it present and executable?</li>
     * </ol>
     */
    private void preflightChecks(File gitRoot, boolean hasOrigin,
                                  String projectId)
            throws MojoException {
        getLog().info("");
        getLog().info("PREFLIGHT CHECKS");
        List<String> warnings = new java.util.ArrayList<>();

        // 1. Git push auth — draft push (sends nothing, tests auth)
        if (hasOrigin) {
            try {
                ReleaseSupport.execCapture(gitRoot,
                        "git", "push", "--dry-run", "origin", "main");
                getLog().info("  Git push:    authenticated  ✓");
            } catch (Exception e) {
                throw new MojoException(
                        "Cannot push to origin. Fix authentication before "
                                + "releasing.\n  Error: " + e.getMessage());
            }
        } else {
            getLog().info("  Git push:    no origin remote (local-only release)");
        }

        // 2. SSH proxy — can we reach the site deploy server?
        if (deploySite) {
            try {
                ReleaseSupport.execCapture(gitRoot,
                        "ssh", "-o", "ConnectTimeout=5",
                        "-o", "BatchMode=yes",
                        "proxy", "echo", "ok");
                getLog().info("  SSH proxy:   reachable  ✓");
            } catch (Exception e) {
                warnings.add("SSH proxy unreachable — site deploy will be "
                        + "skipped. Error: " + e.getMessage());
                getLog().warn("  SSH proxy:   unreachable (site deploy will fail)");
            }
        }

        // 3. gh CLI — installed and authenticated?
        if (hasOrigin) {
            try {
                ReleaseSupport.execCapture(gitRoot, "gh", "auth", "status");
                getLog().info("  gh CLI:      authenticated  ✓");
            } catch (Exception e) {
                warnings.add("gh CLI not available or not authenticated — "
                        + "GitHub Release will be skipped. "
                        + "Run: gh auth login");
                getLog().warn("  gh CLI:      not available (GitHub Release "
                        + "will be skipped)");
            }
        }

        // 4. Maven wrapper
        try {
            ReleaseSupport.resolveMavenWrapper(gitRoot, getLog());
            getLog().info("  Maven:       wrapper found  ✓");
        } catch (Exception e) {
            throw new MojoException(
                    "Maven wrapper (mvnw) not found. Run: mvn wrapper:wrapper");
        }

        if (!warnings.isEmpty()) {
            getLog().info("");
            for (String w : warnings) {
                getLog().warn("  ⚠ " + w);
            }
            getLog().info("");

            // In batch mode (-B), log warnings and continue — batch mode
            // means non-interactive, so prompting would hang or abort.
            // In interactive mode, ask the user to confirm.
            boolean batchMode = System.console() == null
                    && System.getProperty("maven.batch-mode") != null;
            // Also check if stdin is unlikely to be connected (common
            // in CI and Maven subprocess invocations with -B)
            if (batchMode || java.util.Arrays.asList(
                    System.getProperty("sun.java.command", "").split(" "))
                    .contains("-B")) {
                getLog().warn("  Batch mode (-B): proceeding with "
                        + warnings.size() + " warning(s).");
            } else {
                // Prompt for confirmation
                String answer = null;
                java.io.Console console = System.console();
                if (console != null) {
                    answer = console.readLine(
                            "  Continue with %d warning(s)? (yes/no): ",
                            warnings.size());
                } else {
                    System.out.print("\u001B[33m  Continue with " + warnings.size()
                            + " warning(s)? (yes/no): \u001B[0m");
                    System.out.flush();
                    try {
                        answer = new java.io.BufferedReader(
                                new java.io.InputStreamReader(System.in))
                                .readLine();
                    } catch (java.io.IOException e) {
                        // Fall through — treat as "no"
                    }
                }

                if (answer == null
                        || !answer.trim().equalsIgnoreCase("yes")) {
                    throw new MojoException(
                            "Release aborted. Resolve warnings and retry.");
                }
            }
        }
        getLog().info("");
    }

    /**
     * Check that javadoc generation — as the release profile runs it —
     * produces no warnings across every reactor module. On
     * {@code publish} mode any warning aborts the release; on draft
     * mode warnings are logged so the user sees what would block the
     * real release.
     *
     * <p>Skipped when no {@code src/main/java} tree exists anywhere in
     * the reactor (doc-only / POM-only repos have nothing to check).
     *
     * <p>Matches the release path by invoking {@code mvn compile
     * javadoc:jar} across the reactor — the same goal the {@code
     * release} profile uses. {@code -DfailOnError=false
     * -DfailOnWarnings=false} prevent the child build from exiting
     * early so every module's warnings are collected in a single pass.
     *
     * @param gitRoot reactor root whose javadoc is inspected
     * @param publish {@code true} for publish mode (hard fail),
     *                {@code false} for draft mode (warn only)
     * @throws MojoException if publish mode and warnings are present
     */
    private void preflightJavadoc(File gitRoot, boolean publish)
            throws MojoException {
        if (!hasAnyJavaSource(gitRoot)) return;

        List<String> warnings = collectJavadocWarnings(gitRoot);
        getLog().info("");
        if (warnings.isEmpty()) {
            getLog().info("  Javadoc:     warning-free  ✓");
            return;
        }

        getLog().info("  Javadoc:     " + warnings.size()
                + " warning(s)  ✗");
        for (String w : warnings) {
            getLog().warn("    " + w);
        }

        if (publish) {
            throw new MojoException(
                    "Javadoc preflight failed: " + warnings.size()
                            + " warning(s) must be resolved before publish.\n"
                            + "  Convention: every public method needs"
                            + " complete @param / @return / @throws tags.");
        }
        getLog().warn("  (Draft mode — would block publish.)");
        getLog().info("");
    }

    /**
     * Return {@code true} if {@code gitRoot} or any direct subdirectory
     * contains a {@code src/main/java} tree. Covers both single-module
     * and flat multi-module reactor layouts.
     *
     * @param gitRoot the repository root to search
     * @return {@code true} if at least one Java source tree is present
     */
    private boolean hasAnyJavaSource(File gitRoot) {
        if (new File(gitRoot, "src/main/java").isDirectory()) return true;
        File[] entries = gitRoot.listFiles();
        if (entries == null) return false;
        for (File entry : entries) {
            if (!entry.isDirectory()) continue;
            if (new File(entry, "src/main/java").isDirectory()) return true;
        }
        return false;
    }

    /**
     * Run {@code mvn compile javadoc:jar} at {@code gitRoot} to mirror
     * the release's javadoc path across every reactor module, and
     * return every line matching {@code warning:} stripped of the
     * leading {@code [WARNING] } prefix. Tolerates subprocess failure
     * so the release does not abort on an infrastructure issue (a real
     * javadoc failure will resurface during the subsequent build
     * phase).
     *
     * @param gitRoot the reactor root in which to run javadoc
     * @return the captured warning lines in encounter order; empty if
     *         javadoc produced no warnings or the subprocess failed
     */
    private List<String> collectJavadocWarnings(File gitRoot) {
        List<String> warnings = new ArrayList<>();
        try {
            Process proc = new ProcessBuilder(
                    "mvn", "-q", "-B",
                    "compile", "javadoc:jar",
                    "-DskipTests",
                    "-DfailOnError=false",
                    "-DfailOnWarnings=false")
                    .directory(gitRoot)
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(),
                            StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.contains("warning:")) continue;
                    warnings.add(line.replaceFirst(
                            "^\\[WARNING\\] ", "").strip());
                }
            }
            proc.waitFor();
        } catch (IOException | InterruptedException e) {
            getLog().debug("Javadoc preflight subprocess failed: "
                    + e.getMessage());
        }
        return warnings;
    }

    private void logAudit(File gitRoot, File mvnw, String branch,
                          String releaseBranch, String oldVersion,
                          String projectId) throws MojoException {
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
        getLog().info("  Publish:           "+ publish);
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
            throws MojoException {
        String stagingDisk = ReleaseSupport.siteStagingPath(releaseDisk);

        getLog().info("Deploying site to staging...");
        ReleaseSupport.cleanRemoteSiteDir(gitRoot, getLog(), stagingDisk);
        ReleaseSupport.exec(gitRoot, getLog(),
                mvnw.getAbsolutePath(), "site:deploy", "-B", "-T", "1",
                "-Dsite.deploy.url=" + stagingUrl);
        ReleaseSupport.swapRemoteSiteDir(gitRoot, getLog(), releaseDisk);

        // GitHub Pages publishing is now handled by deploy-site-publish
        // (the former publish-site goal was merged into deploy-site in v83)
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
            throws MojoException {
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
        } catch (Exception e) {
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
            } catch (Exception e) {
                getLog().warn("Could not close milestone (release succeeded): "
                        + e.getMessage());
                getLog().warn("Close manually: gh api repos/" + issueRepo
                        + "/milestones/1 -X PATCH -f state=closed");
            }
        }
    }
}
