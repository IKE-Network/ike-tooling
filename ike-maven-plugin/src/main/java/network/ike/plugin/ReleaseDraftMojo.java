package network.ike.plugin;

import network.ike.plugin.release.ReleaseContext;
import network.ike.plugin.release.ReleaseRequest;
import network.ike.plugin.release.RetrySchedule;
import network.ike.plugin.release.WorktreeGuard;
import network.ike.plugin.release.central.CentralOutcome;
import network.ike.plugin.release.central.CentralPhase;
import network.ike.plugin.release.finalize.FinalizeInput;
import network.ike.plugin.release.finalize.FinalizePhase;
import network.ike.plugin.release.local.LocalInput;
import network.ike.plugin.release.local.LocalPhase;
import network.ike.plugin.release.nexus.NexusOutcome;
import network.ike.plugin.release.nexus.NexusPhase;
import network.ike.plugin.release.prep.PrepOutcome;
import network.ike.plugin.release.prep.ReleasePrep;
import network.ike.plugin.release.report.DraftRenderer;
import network.ike.plugin.release.report.ReleaseReport;
import network.ike.plugin.support.AbstractGoalMojo;
import network.ike.plugin.support.GoalReportSpec;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
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
 *   <li>Bump to next SNAPSHOT version, verify, install, commit</li>
 * </ol>
 *
 * <p>The post-bump build runs {@code install} (not just
 * {@code verify}) so the new {@code -SNAPSHOT} artifacts land in
 * the local repository. For a self-hosting repo whose POM pins
 * {@code ike-maven-plugin} to {@code ${project.version}}, this
 * means the next {@code ike:*} invocation — including an
 * {@code ike:release-cascade} walk to the next member — resolves
 * the plugin without a manual {@code install}.
 * IKE-Network/ike-issues#486.
 *
 * <p><strong>External phase (most reversible first, irreversible last):</strong></p>
 * <ol>
 *   <li>Deploy site from tagged commit (overwritable — safe to retry)</li>
 *   <li>Deploy artifacts from tagged commit — to Maven Central via
 *       JReleaser when {@code ike.publishToCentral} is set, else to
 *       the internal Nexus (irreversible — last)</li>
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
@Mojo(name = IkeGoal.NAME_RELEASE_DRAFT, projectRequired = false, aggregator = true)
public class ReleaseDraftMojo extends AbstractGoalMojo {

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

    /**
     * Publish the site to GitHub Pages after internal site deploy.
     * Uses {@code ike:publish-site} to force-push an orphan commit
     * to the {@code gh-pages} branch.
     */
    @Parameter(property = "publishSite", defaultValue = "true")
    boolean publishSite;

    /**
     * Run {@code site} and {@code site:stage} non-recursively
     * ({@code -N}). Set this to {@code true} when releasing a multi-
     * module aggregator (workspace root) whose subprojects inherit a
     * per-artifactId {@code <site>} URL: with the full reactor active,
     * sibling modules' site:stage runs all target the same staging
     * root and the last-built module overwrites the workspace's own
     * staged site, so {@code publishProjectSiteToGhPages} ships a
     * subproject's content as the workspace's gh-pages root.
     * ike-issues#356.
     *
     * <p>Default {@code false}: standalone subproject releases need
     * the full reactor for their own site (single-module reactors
     * don't collide).
     */
    @Parameter(property = "nonRecursiveSite", defaultValue = "false")
    boolean nonRecursiveSite;

    /**
     * Skip the org-site registration step that runs at the end of a
     * successful release-publish. By default each release invokes
     * {@code ike:site-publish -DupdateSite=false} (#398) so the IKE
     * Network landing page at https://ike.network/ picks up the
     * just-released version automatically. Pass
     * {@code -Dike.skip.orgSite=true} to skip when batching releases
     * or working offline.
     *
     * <p>Best-effort: failure of the org-site step warns but does
     * not fail the release. ike-issues#367.
     */
    @Parameter(property = "ike.skip.orgSite", defaultValue = "false")
    boolean skipOrgSite;

    /**
     * Publish the release to Maven Central via JReleaser instead of
     * deploying to the internal Nexus. Opt-in: a repository sets
     * {@code <ike.publishToCentral>true</ike.publishToCentral>} in
     * its POM properties. When {@code false} (default), releases
     * deploy to Nexus as before.
     *
     * <p>When enabled, the release does a signed staging deploy to a
     * local directory, prunes the Maven 4 {@code -build.pom}
     * artifacts (not published to Central), then uploads via
     * {@code jreleaser:deploy}. IKE-Network/ike-issues#445.
     */
    @Parameter(property = "ike.publishToCentral", defaultValue = "false")
    boolean publishToCentral;

    /**
     * Maximum attempts for the Nexus deploy phase. Default 3.
     * The Nexus phase is mandatory: failure after all attempts
     * aborts the release before any tag/main push.
     * IKE-Network/ike-issues#482.
     */
    @Parameter(property = "ike.deploy.nexus.maxAttempts",
            defaultValue = "3")
    int nexusDeployMaxAttempts;

    /**
     * Inter-attempt backoff for the Nexus deploy phase, as a
     * comma-separated list of seconds. Default {@code 30,120,300}
     * (30 s / 2 m / 5 m). Index {@code i} is the wait between
     * attempts {@code i+1} and {@code i+2}; if shorter than
     * {@code maxAttempts - 1}, the last entry is reused.
     */
    @Parameter(property = "ike.deploy.nexus.backoffSeconds",
            defaultValue = "30,120,300")
    String nexusDeployBackoffSeconds;

    /**
     * Skip the Nexus deploy phase entirely. A release without
     * a Nexus deploy is incomplete by design — use only for
     * controlled debugging.
     */
    @Parameter(property = "ike.skipNexusDeploy", defaultValue = "false")
    boolean skipNexusDeploy;

    /**
     * Maximum attempts for the Maven Central deploy phase.
     * Default 5. The Central phase is best-effort: failure after
     * all attempts does <em>not</em> abort the release. Nexus
     * already has the artifact (phase 1), so the team is
     * unblocked; the post-release report flags the Central gap
     * for human follow-up.
     */
    @Parameter(property = "ike.deploy.central.maxAttempts",
            defaultValue = "5")
    int centralDeployMaxAttempts;

    /**
     * Inter-attempt backoff for the Maven Central deploy phase,
     * as a comma-separated list of seconds. Default
     * {@code 60,300,900,1800,3600} (1 m / 5 m / 15 m / 30 m /
     * 60 m). Longer than the Nexus backoff to ride through
     * Sonatype's validation-queue throttling under load. Same
     * shape rules as {@link #nexusDeployBackoffSeconds}.
     */
    @Parameter(property = "ike.deploy.central.backoffSeconds",
            defaultValue = "60,300,900,1800,3600")
    String centralDeployBackoffSeconds;

    /**
     * Skip the Maven Central deploy phase. Defaults to false;
     * the step is also skipped automatically (with a warning,
     * not a hard fail) when {@code
     * JRELEASER_DEPLOY_MAVEN_MAVENCENTRAL_USERNAME} or
     * {@code _PASSWORD} are absent from the environment.
     */
    @Parameter(property = "ike.skipCentralDeploy", defaultValue = "false")
    boolean skipCentralDeploy;

    /**
     * Run the Maven Central deploy phase asynchronously
     * (IKE-Network/ike-issues#484). Defaults to true; opt out
     * with {@code -Dike.deploy.central.async=false} for a
     * release where the operator wants to block on Central
     * completion.
     *
     * <p>When true, after Nexus phase 1 succeeds the release
     * goal writes a {@code PENDING} sentinel under
     * {@code ~/.cache/ike-release/}, spawns a detached
     * subprocess that runs the JReleaser upload with the same
     * retry budget as the sync path, and returns control
     * immediately. The subprocess rewrites the sentinel on
     * completion. Track outcomes with {@code ike:central-status}.
     *
     * <p>Primary motivation: a foundation cascade
     * ({@code ike-tooling → ike-docs → ike-platform → ...})
     * no longer waits for Sonatype validation between members,
     * since inter-cascade dependencies resolve through Nexus.
     */
    @Parameter(property = "ike.deploy.central.async",
            defaultValue = "true")
    boolean centralDeployAsync;

    /**
     * Override the sentinel directory for async Central
     * deploys. Defaults to {@link CentralDeploySentinel#DEFAULT_DIR}.
     * Mainly for tests; production releases should use the
     * default so {@code ike:central-status} finds them.
     */
    @Parameter(property = "ike.central.sentinelDir")
    String centralSentinelDir;

    // ── Deploy-phase outcome tracking (#482) ─────────────────────
    // Written by deployArtifacts(), read by the post-release log
    // and buildReleaseReport(). Defaults represent the "did not
    // run" state — appropriate for a draft preview or a release
    // aborted before deploy. Outcome records were extracted to
    // their future packages during the Phase 4 P1 prep commit
    // (IKE-Network/ike-issues#489).
    private NexusOutcome nexusOutcome = NexusOutcome.initial();
    private CentralOutcome centralOutcome = CentralOutcome.initial();

    /**
     * GitHub repository for issue tracking, used to look up a milestone
     * named {@code <artifactId> v<version>} for release notes generation.
     * If the milestone exists, its closed issues are formatted as the
     * GitHub Release body. Falls back to {@code --generate-notes} if
     * no milestone is found.
     */
    @Parameter(property = "issueRepo", defaultValue = "IKE-Network/ike-issues")
    String issueRepo;

    /**
     * Proceed past preflight <em>warnings</em> (IKE-Network/ike-issues#428).
     *
     * <p>By default {@code ike:release-publish} fails when the
     * preflight reports any warning — a missing release milestone,
     * commits with no issue trailer, a git-push auth hiccup. Set this
     * to {@code true} to release anyway. Preflight <em>errors</em>
     * (a {@code -SNAPSHOT} surviving in a POM, a missing Maven
     * wrapper, an unclean working tree) are never ignorable and abort
     * the release regardless of this flag.
     */
    @Parameter(property = "ike.release.ignoreWarnings", defaultValue = "false")
    boolean ignoreWarnings;

    /** Override working directory for tests. If null, uses current directory. */
    File baseDir;

    /** Creates this goal instance. */
    public ReleaseDraftMojo() {}

    @Override
    protected GoalReportSpec runGoal() throws MojoException {
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
                        + " && mvn " + IkeGoal.RELEASE_PUBLISH.qualified()
                        + "\n"
                        + "  Fresh:  git branch -D " + releaseBranch
                        + " && mvn " + IkeGoal.RELEASE_PUBLISH.qualified());
            }
        }

        // ── Build request + early (mvnw-less) context for ReleasePrep ──
        ReleaseRequest request = new ReleaseRequest(
                releaseVersion, nextVersion, publish, skipVerify, allowBranch,
                publishSite, nonRecursiveSite, skipOrgSite, publishToCentral,
                nexusDeployMaxAttempts, nexusDeployBackoffSeconds, skipNexusDeploy,
                centralDeployMaxAttempts, centralDeployBackoffSeconds, skipCentralDeploy,
                centralDeployAsync, centralSentinelDir, issueRepo, ignoreWarnings);
        ReleaseContext earlyCtx = new ReleaseContext(gitRoot, null, getLog(), request);

        // ── Prep phase: B3–B12 (clean worktree, preflight, javadoc,
        //    SNAPSHOT scan, foundation bake, upstream alignment,
        //    commit-timestamp resolution) ──
        PrepOutcome prep = new ReleasePrep(earlyCtx, getSession()).execute();
        String projectId = prep.projectId();
        String releaseTimestamp = prep.releaseTimestamp();

        // ── B10: Draft-mode short-circuit ────────────────────────────
        if (prep.draftMode()) {
            return new DraftRenderer(earlyCtx).render(
                    prep, startDir.toPath(), oldVersion, releaseBranch);
        }

        // ── Release ───────────────────────────────────────────────────

        // Resolve Maven wrapper (publish path only — draft returned above).
        File mvnw = ReleaseSupport.resolveMavenWrapper(gitRoot, getLog());
        ReleaseContext ctx = earlyCtx.withMvnw(mvnw);

        // Build environment audit (needs mvnw for --version)
        logAudit(ctx, currentBranch, releaseBranch, oldVersion, projectId);

        // ── Local phase: B13–B19 (cut branch, set version, install,
        //    pre-flight site, commit, tag, restore, merge, post-bump) ──
        new LocalPhase(ctx).execute(new LocalInput(
                oldVersion, releaseTimestamp, resuming));

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

        // Track gh-pages publish outcome separately from Nexus deploy.
        // Used to gate the "GitHub Pages: ..." line in the release-
        // complete summary so the log does not falsely claim success
        // when the publish actually failed. ike-issues#329.
        boolean ghPagesPublished = !publishSite;  // skipped == "no failure"
        // Detach the worktree to the release tag for the externally-
        // visible deploy steps. The WorktreeGuard restores the worktree
        // to main on any exit path; foreign mid-flight changes are
        // stashed first (ike-issues#373).
        try (WorktreeGuard worktreeGuard = WorktreeGuard.detach(ctx,
                "v" + releaseVersion,
                () -> stashForeignWorktreeChanges(ctx, releaseVersion))) {
            // ── Site generation (must succeed before Nexus deploy) ────
            // A release without a valid site is incomplete. The tag
            // checkout wiped target/, so everything is rebuilt here.
            if (publishSite) {
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

                // 3. Build site (generates JaCoCo HTML from jacoco.exec).
                //    -Drelease.bootstrap.version=oldVersion (X-SNAPSHOT)
                //    activates the releaseSelfSite profile in reactor-
                //    root poms that declare it (#370). See the
                //    "X-SNAPSHOT bootstrap (2 of 2)" comment on the
                //    pre-flight site invocation for the full pattern.
                List<String> buildArgs = new ArrayList<>();
                buildArgs.add(mvnw.getAbsolutePath());
                buildArgs.add("site");
                buildArgs.add("-B");
                buildArgs.add("-T");
                buildArgs.add("1");
                buildArgs.add("-Drelease.bootstrap.version=" + oldVersion);
                if (nonRecursiveSite) buildArgs.add("-N");
                ReleaseSupport.exec(gitRoot, getLog(),
                        buildArgs.toArray(new String[0]));

                // 4. Inject breadcrumbs into JaCoCo reports
                getLog().info("Injecting breadcrumbs into JaCoCo reports...");
                ReleaseSupport.exec(gitRoot, getLog(),
                        mvnw.getAbsolutePath(), "network.ike.tooling:ike-maven-plugin:inject-breadcrumb",
                        "-B", "-T", "1");

                // 5. Stage site (packages for deploy)
                // Clean target/staging/ first — stale content from
                // earlier releases (which used a different staging
                // structure under pre-#304 site URLs) can survive
                // the maven-clean-plugin pass on some configurations
                // and get picked up by publishProjectSiteToGhPages'
                // unwrap heuristics. Caused v6 workspace site to
                // ship a stale v3-era footer. ike-issues#351 v3.
                Path stagingDirToClean = gitRoot.toPath()
                        .resolve("target").resolve("staging");
                if (Files.isDirectory(stagingDirToClean)) {
                    getLog().info("Cleaning stale target/staging/ "
                            + "before site:stage (#351)...");
                    ReleaseSupport.deleteDirectory(stagingDirToClean);
                }
                // -Drelease.bootstrap.version: X-SNAPSHOT bootstrap
                // (see pre-flight site invocation for full pattern).
                List<String> stageArgs = new ArrayList<>();
                stageArgs.add(mvnw.getAbsolutePath());
                stageArgs.add("site:stage");
                stageArgs.add("-B");
                stageArgs.add("-T");
                stageArgs.add("1");
                stageArgs.add("-Drelease.bootstrap.version=" + oldVersion);
                if (nonRecursiveSite) stageArgs.add("-N");
                ReleaseSupport.exec(gitRoot, getLog(),
                        stageArgs.toArray(new String[0]));

                // 6. Publish to gh-pages (while target/staging/ exists).
                // Best-effort — failures warn but don't block Nexus
                // deploy. Pre-#304 this step also did scpexe://proxy
                // site-deploy; that path was retired since the
                // gh-pages publish (public, HTTPS, no LAN/WireGuard
                // dependency) is the canonical site distribution
                // channel.
                try {
                    SiteDeployResult result = deploySiteAndPublish(ctx,
                            projectId, releaseVersion);
                    ghPagesPublished = result.ghPagesPublished();
                } catch (Exception e) {
                    logSiteDeployRetryInstructions(projectId, releaseVersion,
                            e.getMessage());
                    ghPagesPublished = false;
                }

                // Auto-register this release on the IKE Network org-site
                // (ike-issues#367; #398 folded into ike:site-publish).
                // Placed here, inside the try block, for one specific
                // reason: the working tree is checked out at the
                // `v<releaseVersion>` tag right now, so the pom version
                // reads as <releaseVersion> (not the post-release
                // SNAPSHOT bump). That means
                // `mvn ike:site-publish` (short prefix) resolves the
                // plugin via ${project.version} and naturally lands on
                // the just-installed plugin version — no explicit
                // coordinate pin needed.
                //
                // Gates: publishSite + ghPagesPublished prevent
                // advertising a release whose canonical URL 404s;
                // hasOrigin skips the call for local-only repos;
                // skipOrgSite is the operator's opt-out.
                //
                // Best-effort: site-publish failure warns but does not
                // abort the release. Nexus deploy still runs.
                if (publishSite && ghPagesPublished
                        && !skipOrgSite && prep.hasOrigin()) {
                    getLog().info("");
                    getLog().info("Registering release on IKE Network "
                            + "landing page (#367; via "
                            + IkeGoal.SITE_PUBLISH.qualified()
                            + " after #398)...");
                    try {
                        // #398: site convergence — registration is the
                        // LandingPageRegistrationReconciler dimension of
                        // ike:site-publish. The site itself was already
                        // pushed to gh-pages above, so opt out of the
                        // DeployedSiteReconciler with -DupdateSite=false.
                        ReleaseSupport.exec(gitRoot, getLog(),
                                mvnw.getAbsolutePath(),
                                IkeGoal.SITE_PUBLISH.qualified(),
                                "-DupdateSite=false",
                                "-B");
                    } catch (Exception e) {
                        getLog().warn("  ⚠ Org-site registration "
                                + "failed (non-fatal): " + e.getMessage());
                        getLog().warn("    The release itself is "
                                + "complete. To retry: cd to a checkout "
                                + "at the v" + releaseVersion
                                + " tag and run "
                                + "mvn ike:site-publish -DupdateSite=false");
                    }
                }
            }

            // ── Artifact deploy (critical — the actual release) ──────
            // Two-phase deploy (IKE-Network/ike-issues#482):
            //   1. Nexus (mandatory, retried, abort on failure)
            //   2. Maven Central via JReleaser (opt-in via
            //      publishToCentral, retried, best-effort — failure
            //      records the gap but lets tag/main/GH Release
            //      still publish since Nexus already has the artifact).
            // Site was already deployed above (before clean wipes staging).
            deployArtifacts(ctx);
        }
        // WorktreeGuard.close() has restored the worktree to main —
        // rationale for the stash step is on stashForeignWorktreeChanges.

        // ── Finalize: push tag + main, create GitHub Release ─────
        new FinalizePhase(ctx).execute(new FinalizeInput(
                prep.hasOrigin(), projectId, releaseVersion));

        // Pre-#304: this block called cleanRemoteSiteDir to ssh-delete
        // the main-branch snapshot mirror on scpexe://proxy. With the
        // scpexe path retired, there's no remote dir to clean.

        // VCS state file now managed by ws:release for workspace-level
        // releases. Single-repo ike:release does not write VCS state.

        getLog().info("");
        getLog().info("Release " + releaseVersion + " complete.");
        getLog().info("  Tagged: v" + releaseVersion);
        // Two-phase deploy outcome (#482). Nexus is mandatory and a
        // failure aborts before this point, so a successful release
        // by definition has the Nexus outcome marked succeeded — but
        // log defensively in case skipNexusDeploy was set.
        if (nexusOutcome.succeeded()) {
            getLog().info("  Deployed to Nexus (cycle "
                    + nexusOutcome.attempts() + "/"
                    + nexusDeployMaxAttempts + ")");
        } else if (skipNexusDeploy) {
            getLog().warn("  Nexus deploy skipped "
                    + "(ike.skipNexusDeploy=true)");
        }
        if (publishToCentral) {
            if (centralOutcome.asyncSpawned()) {
                getLog().info("  Maven Central: running async — "
                        + "track with `mvn "
                        + IkeGoal.CENTRAL_STATUS.qualified()
                        + "`, tail " + centralOutcome.logPath());
            } else if (centralOutcome.succeeded()) {
                getLog().info("  Published to Maven Central "
                        + "(cycle " + centralOutcome.attempts()
                        + "/" + centralDeployMaxAttempts + ")");
            } else if (centralOutcome.skipReason() != null) {
                getLog().warn("  Maven Central skipped: "
                        + centralOutcome.skipReason());
            } else if (centralOutcome.attempts() > 0) {
                getLog().warn("  Maven Central deploy FAILED after "
                        + centralOutcome.attempts() + "/"
                        + centralDeployMaxAttempts + " cycles — "
                        + "Nexus has v" + releaseVersion + "; "
                        + "retry: checkout v" + releaseVersion
                        + " and run `mvn jreleaser:deploy`");
            }
        }
        // GitHub Pages publish (the canonical site distribution post-#304).
        // Earlier revisions also printed scpexe://proxy URLs (internal
        // mirror at ike.komet.sh); that path was retired in #304 since
        // gh-pages is public, HTTPS, and doesn't depend on
        // LAN/WireGuard reachability.
        if (publishSite) {
            if (ghPagesPublished) {
                // Hybrid structure (#332): current at root, versioned
                // snapshot, latest alias.
                getLog().info("  GitHub Pages:");
                getLog().info("    Current:   https://ike.network/"
                        + projectId + "/");
                getLog().info("    Versioned: https://ike.network/"
                        + projectId + "/" + releaseVersion + "/");
                getLog().info("    Latest:    https://ike.network/"
                        + projectId + "/latest/");
            } else {
                // Don't lie about state. Earlier behavior printed the
                // success line unconditionally; ike-issues#329.
                getLog().warn("  GitHub Pages: ❌ publish failed "
                        + "(see WARNING above)");
                getLog().warn("    Retry: mvn "
                        + IkeGoal.SITE_PUBLISH.qualified() + " "
                        + "-DupdateRegistration=false "
                        + "-DreleaseVersion=" + releaseVersion);
            }
        }
        getLog().info("  Merged to main");
        getLog().info("  Next version: " + nextVersion);

        ReleaseReport report = new ReleaseReport(ctx);
        report.reportCascade(false);

        return new GoalReportSpec(IkeGoal.RELEASE_PUBLISH,
                startDir.toPath(),
                report.build(false, oldVersion, releaseBranch,
                        projectId, releaseTimestamp,
                        nexusOutcome, centralOutcome));
    }

    /**
     * Two-phase artifact deploy (IKE-Network/ike-issues#482).
     *
     * <p>Phase 1 — Nexus. Mandatory. Retried per
     * {@code ike.deploy.nexus.{maxAttempts,backoffSeconds}}.
     * Failure after all attempts aborts the release before any
     * tag/main push.
     *
     * <p>Phase 2 — Maven Central via JReleaser. Opt-in via
     * {@link #publishToCentral}. Retried per
     * {@code ike.deploy.central.{maxAttempts,backoffSeconds}}.
     * Skipped (with a warning, not a hard fail) when Central
     * credentials are absent. Failure after all attempts records
     * the gap on the run but does <em>not</em> throw — Nexus
     * already has the artifact, so the team is unblocked and
     * tag/main/GH Release still publish.
     *
     * @param ctx the per-invocation release context (release working
     *            tree checked out at the {@code v<version>} release tag)
     */
    private void deployArtifacts(ReleaseContext ctx) {
        File gitRoot = ctx.gitRoot();
        File mvnw = ctx.mvnw();
        // ── Phase 1: Nexus ────────────────────────────────────────
        if (skipNexusDeploy) {
            getLog().warn("Skipping Nexus deploy "
                    + "(ike.skipNexusDeploy=true). "
                    + "Release is incomplete for internal consumers.");
        } else {
            nexusOutcome = new NexusPhase(ctx).execute();
        }

        // ── Phase 2: Maven Central (opt-in, best-effort) ─────────
        if (!publishToCentral) {
            return;
        }
        if (skipCentralDeploy) {
            centralOutcome = centralOutcome.withSkipReason("explicit ike.skipCentralDeploy=true");
            getLog().warn("Skipping Maven Central deploy "
                    + "(ike.skipCentralDeploy=true).");
            return;
        }
        String missingCreds = missingCentralCredentials();
        if (missingCreds != null) {
            centralOutcome = centralOutcome.withSkipReason(missingCreds);
            getLog().warn("Skipping Maven Central deploy: "
                    + missingCreds);
            getLog().warn("  Nexus already has v" + releaseVersion
                    + "; internal consumers are unblocked. To push "
                    + "to Central later: set the missing env var(s) "
                    + "(typically via `op run --env-file=~/.config/"
                    + "ike/release.env`), check out v" + releaseVersion
                    + ", and run `mvn jreleaser:deploy`.");
            return;
        }
        if (centralDeployAsync) {
            spawnCentralDeployAsync(ctx);
        } else {
            // Phase 4 §1.1 — execute() returns a completed future; the
            // mojo joins immediately. Phase 5 forks this under a
            // StructuredTaskScope alongside FinalizePhase.
            centralOutcome = new CentralPhase(ctx).execute().join();
        }
    }

    /**
     * Spawn the Maven Central deploy as a detached subprocess
     * (IKE-Network/ike-issues#484). The subprocess runs the retry
     * loop in a bash wrapper that writes its own start/success/
     * failure sentinel under {@code ~/.cache/ike-release/}, so the
     * outcome survives the originating Maven JVM exit.
     *
     * <p>Why bash and not Java: the wrapper has to outlive this
     * Maven invocation. A Java {@code ProcessBuilder.start()}
     * child reparents to launchd cleanly on macOS/Linux, but the
     * inherited stdin/stdout/stderr need explicit detachment to
     * survive a terminal close. A bash {@code nohup ... &} pattern
     * handles both — SIGHUP immunity and full background — with
     * less platform-specific Java code.
     *
     * @param gitRoot the release working tree at v&lt;version&gt;
     * @param mvnw    the Maven wrapper executable
     */
    private void spawnCentralDeployAsync(ReleaseContext ctx) {
        File gitRoot = ctx.gitRoot();
        File mvnw = ctx.mvnw();
        Path sentinelDir = centralSentinelDir == null
                || centralSentinelDir.isBlank()
                        ? CentralDeploySentinel.DEFAULT_DIR
                        : Paths.get(centralSentinelDir);
        String artifactId = gitRoot.getName();
        centralOutcome = centralOutcome
                .withSentinelPath(CentralDeploySentinel
                        .resolvePath(sentinelDir, artifactId, releaseVersion))
                .withLogPath(sentinelDir.resolve(
                        artifactId + "-" + releaseVersion + ".log"));

        try {
            Files.createDirectories(sentinelDir);
        } catch (IOException e) {
            throw new MojoException("Could not create sentinel dir "
                    + sentinelDir + ": " + e.getMessage(), e);
        }

        // Write initial PENDING sentinel before the subprocess starts.
        // The subprocess updates it to SUCCESS/FAILURE on completion;
        // if the subprocess never starts (spawn failure below), the
        // PENDING record is replaced with FAILURE before this method
        // throws.
        Instant now = Instant.now();
        CentralDeploySentinel pending = CentralDeploySentinel.builder()
                .state(CentralDeploySentinel.State.PENDING)
                .artifactId(artifactId)
                .version(releaseVersion)
                .started(now)
                .attempts(0)
                .maxAttempts(centralDeployMaxAttempts)
                .logFile(centralOutcome.logPath())
                .path(centralOutcome.sentinelPath())
                .build();
        pending.write();

        int[] backoff = RetrySchedule.parseSeconds(
                "ike.deploy.central.backoffSeconds",
                centralDeployBackoffSeconds);
        Path scriptPath = sentinelDir.resolve(
                artifactId + "-" + releaseVersion + ".sh");
        String script = renderRetryScript(
                gitRoot.toPath(), mvnw.toPath(),
                centralOutcome.sentinelPath(), centralOutcome.logPath(),
                artifactId, releaseVersion,
                centralDeployMaxAttempts, backoff, now);
        try {
            Files.writeString(scriptPath, script);
            scriptPath.toFile().setExecutable(true);
        } catch (IOException e) {
            markAsyncSpawnFailure(pending,
                    "Could not write retry script: " + e.getMessage());
            throw new MojoException("Could not write Central deploy "
                    + "retry script " + scriptPath + ": "
                    + e.getMessage(), e);
        }

        // Detach: `bash -c "nohup <script> < /dev/null > /dev/null
        // 2>&1 &"`. The outer bash forks the script into the
        // background, immune to SIGHUP, and exits in microseconds.
        // Inherited env (JRELEASER_*, PATH, etc.) propagates to the
        // script. The script writes its own log via $LOG.
        String spawnCommand = "nohup '" + scriptPath + "' "
                + "< /dev/null > /dev/null 2>&1 &";
        ProcessBuilder pb = new ProcessBuilder("bash", "-c", spawnCommand);
        pb.directory(gitRoot);
        try {
            Process p = pb.start();
            int exit = p.waitFor();
            if (exit != 0) {
                markAsyncSpawnFailure(pending,
                        "bash forker exited " + exit);
                throw new MojoException("Could not spawn detached "
                        + "Central deploy: bash exited " + exit);
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            markAsyncSpawnFailure(pending,
                    "Spawn failed: " + e.getMessage());
            throw new MojoException("Could not spawn detached "
                    + "Central deploy: " + e.getMessage(), e);
        }

        centralOutcome = centralOutcome.withAsyncSpawned(true);
        getLog().info("Spawned async Maven Central deploy "
                + "(IKE-Network/ike-issues#484).");
        getLog().info("  Sentinel: " + centralOutcome.sentinelPath());
        getLog().info("  Log:      " + centralOutcome.logPath());
        getLog().info("  Track:    mvn " + IkeGoal.CENTRAL_STATUS.qualified());
    }

    /**
     * Rewrite a {@code PENDING} sentinel to {@code FAILURE} when
     * the async spawn itself fails (script unwritable, bash exits
     * non-zero). Without this the sentinel would stay {@code
     * PENDING} forever despite no subprocess being alive.
     *
     * @param pending the PENDING sentinel to update
     * @param reason  short failure summary
     */
    private void markAsyncSpawnFailure(CentralDeploySentinel pending,
                                        String reason) {
        try {
            pending.toBuilder()
                    .state(CentralDeploySentinel.State.FAILURE)
                    .finished(Instant.now())
                    .lastError(reason)
                    .build()
                    .write();
        } catch (RuntimeException ignored) {
            // Best-effort cleanup — surface the original failure
            // upstream rather than masking it with a sentinel-write
            // error.
        }
    }

    /**
     * Render the bash retry-loop script that runs
     * {@code jreleaser:deploy} with bounded retries and rewrites
     * the sentinel on each transition. Static + side-effect-free
     * so the script body is straightforward to unit-test.
     *
     * <p>The script runs inside a temporary {@code git worktree}
     * at the {@code v<version>} tag — critical for correctness
     * since the main worktree is restored to {@code main} and
     * bumped to next-SNAPSHOT immediately after the spawn.
     * Without this isolation, JReleaser would read the post-bump
     * pom.xml, see a snapshot version, and silently skip the
     * Sonatype deployer ({@code [WARNING] Deployer ...:sonatype
     * is not enabled. Skipping}).
     *
     * <p>Each retry cycle is logged to a per-cycle file; on
     * mvn-exit-0 the script asserts the log contains
     * {@code uploaded as deployment} (proves a real upload
     * happened) AND does not contain {@code is not enabled.
     * Skipping} (catches deployer-disabled silent no-op).
     * Either check failing marks the cycle as a retry-eligible
     * failure rather than success. A {@code Deployment timeout
     * exceeded} line — JReleaser giving up its publish poll — is
     * not a failure: the upload succeeded, so the cycle passes
     * and a {@code note} is recorded on the sentinel.
     *
     * <p>The worktree is cleaned up via {@code trap EXIT}, so
     * even an interrupted subprocess leaves no stray git state.
     *
     * @param gitRoot       absolute repository root
     * @param mvnw          absolute path to the Maven wrapper
     * @param sentinel      absolute path to the sentinel file
     * @param log           absolute path to the deploy log file
     * @param artifactId    project artifactId
     * @param version       release version (matches v&lt;version&gt; tag)
     * @param maxAttempts   configured max attempts
     * @param backoff       inter-attempt waits in seconds
     * @param started       deploy start instant (UTC)
     * @return the script source
     */
    static String renderRetryScript(Path gitRoot, Path mvnw,
                                     Path sentinel, Path log,
                                     String artifactId, String version,
                                     int maxAttempts, int[] backoff,
                                     Instant started) {
        StringBuilder backoffArr = new StringBuilder();
        for (int i = 0; i < backoff.length; i++) {
            if (i > 0) backoffArr.append(' ');
            backoffArr.append(backoff[i]);
        }
        // The script is intentionally explicit (no Maven helpers) so a
        // human can read it, run it directly to debug, or copy the
        // jreleaser:deploy line for a manual retry.
        return """
                #!/bin/bash
                # ike:release-publish Central deploy retry wrapper
                # (IKE-Network/ike-issues#484). Generated %s.
                # Safe to delete after sentinel reaches SUCCESS/FAILURE.
                set -uo pipefail

                ARTIFACT_ID="%s"
                VERSION="%s"
                STARTED="%s"
                SENTINEL="%s"
                LOG="%s"
                GIT_ROOT="%s"
                MVNW="%s"
                MAX_ATTEMPTS=%d
                BACKOFFS=(%s)

                # Isolated worktree at the release tag — see method
                # javadoc for why this is required.
                WORKTREE_PARENT="$(mktemp -d -t ike-release-XXXXXX)"
                WORKTREE="$WORKTREE_PARENT/$ARTIFACT_ID-$VERSION"

                cleanup() {
                  if [ -n "${WORKTREE:-}" ] && [ -d "$WORKTREE" ]; then
                    git -C "$GIT_ROOT" worktree remove --force "$WORKTREE" >> "$LOG" 2>&1 || true
                  fi
                  if [ -n "${WORKTREE_PARENT:-}" ] && [ -d "$WORKTREE_PARENT" ]; then
                    rm -rf "$WORKTREE_PARENT"
                  fi
                }
                trap cleanup EXIT

                write_sentinel() {
                  local state="$1"
                  local extra="$2"
                  local tmp="${SENTINEL}.tmp"
                  {
                    echo "#ike:release-publish Central deploy sentinel"
                    echo "state=$state"
                    echo "artifactId=$ARTIFACT_ID"
                    echo "version=$VERSION"
                    echo "started=$STARTED"
                    # Only a terminal state has a finish time. A PENDING
                    # refresh must not write `finished` — it would read
                    # as a completed deploy in ike:central-status.
                    if [ "$state" != "PENDING" ]; then
                      echo "finished=$(date -u +%%Y-%%m-%%dT%%H:%%M:%%SZ)"
                    fi
                    echo "attempts=$ATTEMPTS"
                    echo "maxAttempts=$MAX_ATTEMPTS"
                    echo "logFile=$LOG"
                    echo "pid=$$"
                    if [ -n "$extra" ]; then
                      printf '%%s\\n' "$extra"
                    fi
                  } > "$tmp"
                  mv -f "$tmp" "$SENTINEL"
                }

                ATTEMPTS=0
                DEPLOY_NOTE=""
                {
                  echo "[$(date)] Async Central deploy starting"
                  echo "  artifact: $ARTIFACT_ID-$VERSION"
                  echo "  git root: $GIT_ROOT"
                  echo "  worktree: $WORKTREE"
                  echo "  max cycles: $MAX_ATTEMPTS"
                  echo "  backoffs (s): ${BACKOFFS[*]}"
                } >> "$LOG"

                # One-time: create the isolated worktree at v<version>.
                # All deploy cycles reuse it (clean deploy inside the
                # worktree wipes its own target/).
                if ! git -C "$GIT_ROOT" worktree add "$WORKTREE" "v$VERSION" >> "$LOG" 2>&1; then
                  write_sentinel "FAILURE" "lastError=could not create worktree at v$VERSION"
                  echo "[$(date)] FAILURE: worktree add" >> "$LOG"
                  exit 1
                fi

                run_attempt() {
                  local attempt_log="$WORKTREE/target/jreleaser-cycle-$ATTEMPTS.log"
                  # Step 1: stage signed artifacts to the worktree's
                  # target/staging-deploy.
                  if ! (cd "$WORKTREE" && "$MVNW" clean deploy -B -T 1 \\
                      -P release,signArtifacts \\
                      -DaltDeploymentRepository=local::file://"$WORKTREE/target/staging-deploy") >> "$LOG" 2>&1; then
                    echo "[$(date)] Stage failed on cycle $ATTEMPTS" >> "$LOG"
                    return 1
                  fi
                  # Step 2: prune -build.pom (Maven 4 build POMs, not
                  # published to Central).
                  find "$WORKTREE/target/staging-deploy" \\
                      \\( -name '*-build.pom' -o -name '*-build.pom.asc' \\
                         -o -name '*-build.pom.md5' \\
                         -o -name '*-build.pom.sha1' \\
                         -o -name '*-build.pom.sha256' \\
                         -o -name '*-build.pom.sha512' \\) \\
                      -delete >> "$LOG" 2>&1 || true
                  # Step 3: upload via JReleaser. Capture to per-cycle
                  # log for validation.
                  if ! (cd "$WORKTREE" && "$MVNW" jreleaser:deploy -N -B) > "$attempt_log" 2>&1; then
                    cat "$attempt_log" >> "$LOG"
                    echo "[$(date)] JReleaser upload failed on cycle $ATTEMPTS" >> "$LOG"
                    return 1
                  fi
                  cat "$attempt_log" >> "$LOG"
                  # Step 4: validate. JReleaser silently skips when the
                  # deployer isn't enabled (e.g. snapshot version), and
                  # the mvn exit code is still 0. Confirm a real upload
                  # happened — see method javadoc.
                  if grep -q "is not enabled. Skipping" "$attempt_log"; then
                    echo "[$(date)] JReleaser logged 'Skipping' — deployer not active" >> "$LOG"
                    return 1
                  fi
                  if ! grep -q "uploaded as deployment" "$attempt_log"; then
                    echo "[$(date)] JReleaser did not log an uploaded deployment" >> "$LOG"
                    return 1
                  fi
                  # The upload succeeded. JReleaser may still have timed
                  # out polling Sonatype for the PUBLISHED transition —
                  # that is NOT a deploy failure (the bundle is on
                  # Sonatype's side and will publish), but record a note
                  # so ike:central-status flags publication as
                  # unconfirmed. IKE-Network/ike-issues#484.
                  if grep -q "Deployment timeout exceeded" "$attempt_log"; then
                    DEPLOY_NOTE="note=upload accepted by Sonatype; JReleaser poll for PUBLISHED timed out - publication unconfirmed, verify on the Central Portal"
                    echo "[$(date)] Upload confirmed; JReleaser poll timed out before PUBLISHED (non-fatal)" >> "$LOG"
                  fi
                  return 0
                }

                while [ $ATTEMPTS -lt $MAX_ATTEMPTS ]; do
                  ATTEMPTS=$((ATTEMPTS + 1))
                  echo "[$(date)] Cycle $ATTEMPTS/$MAX_ATTEMPTS" >> "$LOG"
                  # Refresh PENDING with current cycle count so
                  # ike:central-status reflects progress.
                  write_sentinel "PENDING" ""
                  if run_attempt; then
                    write_sentinel "SUCCESS" "$DEPLOY_NOTE"
                    echo "[$(date)] SUCCESS on cycle $ATTEMPTS" >> "$LOG"
                    exit 0
                  fi
                  if [ $ATTEMPTS -lt $MAX_ATTEMPTS ]; then
                    IDX=$((ATTEMPTS - 1))
                    if [ $IDX -ge ${#BACKOFFS[@]} ]; then
                      IDX=$((${#BACKOFFS[@]} - 1))
                    fi
                    WAIT=${BACKOFFS[$IDX]}
                    echo "[$(date)] Sleeping ${WAIT}s before next cycle" >> "$LOG"
                    sleep "$WAIT"
                  fi
                done

                write_sentinel "FAILURE" "lastError=exhausted $MAX_ATTEMPTS cycles; see $LOG"
                echo "[$(date)] FAILURE after $ATTEMPTS cycles" >> "$LOG"
                exit 1
                """.formatted(
                    started.toString(),
                    artifactId, version, started.toString(),
                    sentinel.toAbsolutePath(),
                    log.toAbsolutePath(),
                    gitRoot.toAbsolutePath(),
                    mvnw.toAbsolutePath(),
                    maxAttempts, backoffArr);
    }


    /**
     * Returns {@code null} when Maven Central credentials are
     * present in the environment; otherwise a human-readable
     * reason naming the missing variables.
     *
     * <p>JReleaser reads
     * {@code JRELEASER_DEPLOY_MAVEN_MAVENCENTRAL_USERNAME} and
     * {@code _PASSWORD} (see {@code ike-base-parent} JReleaser
     * config). Both must be present and non-blank.
     *
     * @return missing-credentials reason, or {@code null} when all
     *         required env vars are set
     */
    private String missingCentralCredentials() {
        List<String> missing = new ArrayList<>();
        String[] required = {
                "JRELEASER_DEPLOY_MAVEN_MAVENCENTRAL_USERNAME",
                "JRELEASER_DEPLOY_MAVEN_MAVENCENTRAL_PASSWORD"
        };
        for (String name : required) {
            String value = System.getenv(name);
            if (value == null || value.isBlank()) {
                missing.add(name);
            }
        }
        if (missing.isEmpty()) {
            return null;
        }
        return "missing env var(s) " + String.join(", ", missing);
    }




    private void logAudit(ReleaseContext ctx, String branch,
                          String releaseBranch, String oldVersion,
                          String projectId) throws MojoException {
        File gitRoot = ctx.gitRoot();
        File mvnw = ctx.mvnw();
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
     * Result of {@link #deploySiteAndPublish}: tracks gh-pages publish
     * outcome. Pre-#304 also tracked scpexe site-deploy independently
     * (ike-issues#339) but the scpexe path was retired with #304.
     *
     * @param ghPagesPublished {@code true} if gh-pages publish
     *                         succeeded or was skipped;
     *                         {@code false} if attempted and failed
     */
    private record SiteDeployResult(boolean ghPagesPublished) { }

    /**
     * Deploy the staged site and publish to GitHub Pages.
     *
     * <p>Called only after site generation and Nexus deploy have both
     * succeeded. Failures here are caught by the caller and reported
     * as warnings with retry instructions.
     *
     * @return a {@link SiteDeployResult} carrying the gh-pages publish
     *         flag — {@code true} if the publish succeeded or was
     *         skipped, {@code false} if attempted and failed. Pre-#304
     *         the result also tracked scpexe site-deploy independently
     *         (ike-issues#339); that path is retired.
     */
    private SiteDeployResult deploySiteAndPublish(ReleaseContext ctx,
                                          String projectId, String version)
            throws MojoException {
        File gitRoot = ctx.gitRoot();
        File mvnw = ctx.mvnw();
        boolean ghPagesPublished = !publishSite;  // skipped == "no failure to report"

        // Publish to GitHub Pages. Pre-#304 this came before the
        // scpexe site:deploy step (so the deploy's staging-mirror
        // wouldn't corrupt target/staging/, per ike-issues#312); now
        // it's the only publish target.
        if (publishSite) {
            String remoteUrl = ReleaseSupport.getRemoteUrl(gitRoot, "origin");
            if (remoteUrl == null) {
                getLog().info("  Skipping gh-pages publish "
                        + "(no 'origin' remote)");
                ghPagesPublished = true;  // intentionally skipped, not failed
            } else {
                Path stagingDir = gitRoot.toPath()
                        .resolve("target").resolve("staging");
                Path siteDir = gitRoot.toPath()
                        .resolve("target").resolve("site");

                // Empty-staging fallback (ike-issues#334). mvn site:stage
                // is a no-op for single-module projects (it's designed
                // to aggregate child sites in a multi-module reactor).
                // For single-module projects target/staging/ is created
                // empty and target/site/ has the rendered content.
                // Earlier behavior shipped the empty staging dir as the
                // gh-pages tree, producing a .nojekyll-only branch and
                // a 404 at https://ike.network/<projectId>/. This block
                // detects that case and substitutes target/site/.
                Path publishSource = stagingDir;
                try {
                    if (Files.isDirectory(stagingDir)
                            && ReleaseSupport.isEmptyDirectory(stagingDir)
                            && Files.isDirectory(siteDir)
                            && !ReleaseSupport.isEmptyDirectory(siteDir)) {
                        getLog().info("  target/staging/ is empty — "
                                + "publishing target/site/ instead "
                                + "(single-module project; site:stage "
                                + "has no children to aggregate). "
                                + "ike-issues#334.");
                        publishSource = siteDir;
                    }
                } catch (MojoException emptyCheckFailed) {
                    // Fall through with stagingDir; the publish call
                    // will produce a clearer error.
                    getLog().debug("  Could not inspect staging/site "
                            + "directories: " + emptyCheckFailed.getMessage());
                }

                try {
                    ReleaseSupport.publishProjectSiteToGhPages(
                            publishSource, remoteUrl, getLog(),
                            projectId, version);
                    ghPagesPublished = true;
                } catch (MojoException e) {
                    // Log the full cause chain — earlier behavior dropped
                    // it, leaving only the wrapper message visible.
                    // ike-issues#329.
                    getLog().warn("  ⚠ gh-pages publish failed (non-fatal): "
                            + e.getMessage());
                    Throwable cause = e.getCause();
                    while (cause != null) {
                        getLog().warn("    caused by: "
                                + cause.getClass().getSimpleName()
                                + (cause.getMessage() != null
                                        ? ": " + cause.getMessage()
                                        : ""));
                        cause = cause.getCause();
                    }
                    getLog().warn("    To retry: from a checkout of v"
                            + version + " with 'origin' remote, run "
                            + "mvn " + IkeGoal.SITE_PUBLISH.qualified() + " "
                            + "-DupdateRegistration=false "
                            + "-DreleaseVersion=" + version);
                }
            }
        }

        // Pre-#304: scpexe site-deploy ran here. Retired with #304;
        // gh-pages publish above is the canonical site distribution.

        return new SiteDeployResult(ghPagesPublished);
    }

    /**
     * Stash any uncommitted worktree changes that accumulated during
     * the external-phase block, so the subsequent {@code git checkout
     * main} can proceed unblocked. ike-issues#373.
     *
     * <p>By the time this runs, the release flow's own commits have
     * already shipped (set-version → tag → restore-project.version →
     * merge to main → post-release bump → external deploys). Anything
     * captured by the stash is strictly foreign worktree state:
     * operator edits, the #358 gh-pages on-disk leak files, stray
     * tool output, etc.
     *
     * <p>Best-effort: if {@code git status} or {@code git stash} fail
     * for any reason, the {@code git checkout main} that follows
     * will produce its standard error message — same as before this
     * guard existed. The guard only adds capability, it doesn't
     * remove fallback behavior.
     *
     * @param gitRoot        the project's git root
     * @param releaseVersion the release version, used in the stash
     *                       message so the operator can identify
     *                       which release's stash it is
     */
    private void stashForeignWorktreeChanges(ReleaseContext ctx,
                                              String releaseVersion) {
        File gitRoot = ctx.gitRoot();
        String status;
        try {
            status = ReleaseSupport.execCapture(gitRoot,
                    "git", "status", "--porcelain").trim();
        } catch (MojoException e) {
            getLog().debug("  Could not run git status before "
                    + "checkout main (#373): " + e.getMessage());
            return;
        }
        if (status.isEmpty()) return;

        getLog().warn("");
        getLog().warn("Detected mid-flight worktree changes (#373) — "
                + "stashing before 'git checkout main'.");
        for (String line : status.split("\n")) {
            getLog().warn("    " + line);
        }

        String stashMessage = "release-flow: mid-flight changes "
                + "during v" + releaseVersion;
        try {
            ReleaseSupport.exec(gitRoot, getLog(),
                    "git", "stash", "push",
                    "--include-untracked",
                    "-m", stashMessage);
            getLog().warn("");
            getLog().warn("  Stashed as: " + stashMessage);
            getLog().warn("  Recover with: git stash pop");
            getLog().warn("");
        } catch (Exception e) {
            // Couldn't stash — let the subsequent checkout main fail
            // with its standard message. Don't mask the underlying
            // problem.
            getLog().warn("  ⚠ Could not stash mid-flight changes: "
                    + e.getMessage());
            getLog().warn("  'git checkout main' will likely fail; "
                    + "recover manually per the runbook.");
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
        getLog().warn("  git checkout main");
        getLog().warn("");
    }

}
