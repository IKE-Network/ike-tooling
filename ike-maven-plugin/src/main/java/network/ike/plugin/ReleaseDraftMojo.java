package network.ike.plugin;

import network.ike.plugin.release.ReleaseContext;
import network.ike.plugin.release.ReleaseRequest;
import network.ike.plugin.release.RetrySchedule;
import network.ike.plugin.release.WorktreeGuard;
import network.ike.plugin.release.central.CentralOutcome;
import network.ike.plugin.release.central.CentralPhase;
import network.ike.plugin.release.finalize.FinalizeInput;
import network.ike.plugin.release.finalize.FinalizePhase;
import network.ike.plugin.release.nexus.NexusOutcome;
import network.ike.plugin.release.nexus.NexusPhase;
import network.ike.plugin.scaffold.FoundationBaker;
import network.ike.plugin.scaffold.ScaffoldManifest;
import network.ike.plugin.scaffold.ScaffoldManifestIo;
import network.ike.plugin.support.AbstractGoalMojo;
import network.ike.plugin.support.GoalReportBuilder;
import network.ike.plugin.support.GoalReportSpec;
import network.ike.plugin.support.version.CandidateVersionResolver;
import network.ike.plugin.support.version.MavenVersionComparator;
import network.ike.plugin.support.version.SessionCandidateVersionResolver;
import network.ike.workspace.cascade.CascadeEdge;
import network.ike.workspace.cascade.CascadeReporter;
import network.ike.workspace.cascade.EdgeKind;
import network.ike.workspace.cascade.ProjectCascade;
import network.ike.workspace.cascade.ProjectCascadeIo;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

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
            preflightChecks(gitRoot, hasOrigin, projectId, releaseVersion);
        }
        // Javadoc preflight (#168) — runs in both modes. Publish fails
        // on warnings; draft logs them so the user sees what would block
        // the real release.
        preflightJavadoc(gitRoot, publish);

        // SNAPSHOT-in-properties preflight (#175, #177): Maven 4's
        // consumer POM flattener resolves properties and promotes
        // pluginManagement into plugins when writing the released
        // artifact. If a <properties> value ends in -SNAPSHOT it leaks
        // into the released POM as a literal, breaking downstream
        // builds (e.g. ike-parent-105.pom shipped with
        // <ike-tooling.version>112-SNAPSHOT</ike-tooling.version>).
        // Catch it before any mutation — publish hard-fails, draft warns.
        List<SnapshotScanner.Violation> propViolations =
                SnapshotScanner.scanSourceProperties(rootPom);
        if (!propViolations.isEmpty()) {
            String msg = SnapshotScanner.formatViolations(propViolations, gitRoot,
                    propViolations.size() + " SNAPSHOT property value(s) would"
                            + " leak into released POMs:",
                    "  These values are resolved by Maven 4's consumer POM\n"
                    + "  flattener and baked into released artifacts. Bump\n"
                    + "  each property to a released (non-SNAPSHOT) version\n"
                    + "  before re-running the release.");
            if (publish) {
                throw new MojoException(msg);
            }
            getLog().warn(msg);
        }

        // Release-prep foundation bake (#414): when this release owns
        // the scaffold manifest (the ike-tooling release), refresh its
        // foundation: block to the latest released versions so the
        // scaffold zip ships a current compatibility snapshot.
        bakeFoundationSnapshot(gitRoot, draft);

        // Release-prep upstream cascade alignment (#419): bump this
        // repo's ${X.version} pins to the latest released upstreams so
        // a single-repo release never ships on a stale foundation.
        alignUpstreamProperties(gitRoot, draft);

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
            if (publishSite) {
                getLog().info("[DRAFT] Would generate site (must succeed)");
            }
            getLog().info("[DRAFT] Would " + (publishToCentral
                    ? "publish to Maven Central via JReleaser"
                    : "deploy to Nexus") + " from tag v"
                    + releaseVersion + " (critical)");
            if (publishSite) {
                getLog().info("[DRAFT] Would force-push staged site "
                        + "to gh-pages on origin (best-effort)");
                getLog().info("[DRAFT] Would publish at "
                        + "https://ike.network/" + projectId + "/");
            }
            getLog().info("[DRAFT] Would push tag and main to origin");
            getLog().info("[DRAFT] Would create GitHub Release");
            reportCascade(gitRoot, true);
            return new GoalReportSpec(IkeGoal.RELEASE_DRAFT,
                    startDir.toPath(),
                    buildReleaseReport(true, oldVersion, releaseBranch,
                            projectId, releaseTimestamp));
        }

        // ── Release ───────────────────────────────────────────────────

        // Resolve Maven wrapper (requires mvnw or mvn on PATH — skip for draft)
        File mvnw = ReleaseSupport.resolveMavenWrapper(gitRoot, getLog());

        // Build the per-invocation context bundle from this point onward.
        // Pre-mvnw helpers (preflight, bake, align) still take positional
        // params; they migrate to ctx when ReleasePrep is extracted
        // (IKE-Network/ike-issues#489 Commit 5).
        ReleaseRequest request = new ReleaseRequest(
                releaseVersion, nextVersion, publish, skipVerify, allowBranch,
                publishSite, nonRecursiveSite, skipOrgSite, publishToCentral,
                nexusDeployMaxAttempts, nexusDeployBackoffSeconds, skipNexusDeploy,
                centralDeployMaxAttempts, centralDeployBackoffSeconds, skipCentralDeploy,
                centralDeployAsync, centralSentinelDir, issueRepo, ignoreWarnings);
        ReleaseContext ctx = new ReleaseContext(gitRoot, mvnw, getLog(), request);

        // Build environment audit (needs mvnw for --version)
        logAudit(ctx, currentBranch, releaseBranch, oldVersion, projectId);

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

            // Defense in depth (#175, #177): after ${project.version}
            // substitution the only legitimate <version> values are
            // released literals. Scan all POMs for any surviving
            // <version>...-SNAPSHOT</version> before we commit the
            // release tag — this is Layer 2 of the SNAPSHOT preflight.
            List<File> allPoms = ReleaseSupport.findPomFiles(gitRoot);
            List<SnapshotScanner.Violation> versionViolations =
                    SnapshotScanner.scanForSnapshotVersions(allPoms);
            if (!versionViolations.isEmpty()) {
                throw new MojoException(SnapshotScanner.formatViolations(
                        versionViolations, gitRoot,
                        versionViolations.size() + " literal SNAPSHOT <version>"
                                + " element(s) remain after property resolution:",
                        "  These would be baked into the released artifact.\n"
                        + "  Replace each with a released version or resolve via\n"
                        + "  ${project.version} before re-running the release."));
            }
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
        // -N (non-recursive) when releasing an aggregator whose
        // subproject sites would otherwise collide at the staging
        // root (ike-issues#356).
        //
        // ── X-SNAPSHOT bootstrap (2 of 2) ─────────────────────────────
        // ike-issues#370.
        //
        // Every `mvn site` / `mvn site:stage` invocation in this mojo
        // passes -Drelease.bootstrap.version=<oldVersion>. oldVersion
        // is the pre-release pom version (i.e., X-SNAPSHOT, where X is
        // the version about to be released — captured at line ~139,
        // before setPomVersion runs).
        //
        // The property activates the releaseSelfSite profile in any
        // reactor-root pom that declares it (currently just ike-tooling
        // itself, which has the cycle problem). Inside that profile,
        // ike-maven-plugin is bound at <version>${release.bootstrap.
        // version}</version> — i.e., at X-SNAPSHOT, which is a
        // DIFFERENT GAV than the reactor submodules (set to X by this
        // mojo's setPomVersion). Different GAV → no graph edge to a
        // submodule → no reactor cycle. Maven flags the cycle at
        // reactor evaluation time, so the indirection has to live in
        // the pom; we just supply the property value.
        //
        // Why X-SNAPSHOT is guaranteed in ~/.m2: this mojo's pre-
        // release step (above) runs `mvn clean install` BEFORE any
        // version bump, installing X-SNAPSHOT locally. Subsequent
        // site invocations resolve the plugin descriptor from there.
        //
        // No-op for projects that do not declare the releaseSelfSite
        // profile — setting a property Maven does not see has no
        // effect, so this is safe to pass unconditionally.
        //
        // THE OTHER HALF OF THIS PATTERN — see
        //   ike-tooling/pom.xml
        // (search "X-SNAPSHOT bootstrap (1 of 2)") for the profile
        // declaration that consumes ${release.bootstrap.version}.
        //
        // Note: only `mvn site` / `mvn site:stage` invocations pass
        // the property. Other release-flow `mvn` calls (verify, deploy,
        // site-publish, etc.) stay outside the profile and resolve
        // plugin coords via pluginManagement — the standard self-host
        // pattern, which works because pluginManagement does not
        // create reactor edges the way live <plugins> does.
        if (publishSite) {
            getLog().info("Building site (pre-flight check)...");
            List<String> siteArgs = new ArrayList<>();
            siteArgs.add(mvnw.getAbsolutePath());
            siteArgs.add("site");
            siteArgs.add("site:stage");
            siteArgs.add("-B");
            siteArgs.add("-T");
            siteArgs.add("1");
            siteArgs.add("-Drelease.bootstrap.version=" + oldVersion);
            if (nonRecursiveSite) siteArgs.add("-N");
            ReleaseSupport.exec(gitRoot, getLog(),
                    siteArgs.toArray(new String[0]));
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

        // Verify AND install the new SNAPSHOT (IKE-Network/ike-issues#486).
        // `install` (not just `verify`) puts the post-bump -SNAPSHOT in
        // the local repo so a self-hosting repo — whose POM pins
        // ike-maven-plugin to ${project.version} — can run the next
        // ike:* goal (or an ike:release-cascade walk to the next
        // member) without a manual `mvn install` first.
        ReleaseSupport.exec(gitRoot, getLog(),
                mvnw.getAbsolutePath(), "clean", "install", "-B", "-T", "1");

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
                        && !skipOrgSite && hasOrigin) {
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
                hasOrigin, projectId, releaseVersion));

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

        reportCascade(gitRoot, false);

        return new GoalReportSpec(IkeGoal.RELEASE_PUBLISH,
                startDir.toPath(),
                buildReleaseReport(false, oldVersion, releaseBranch,
                        projectId, releaseTimestamp));
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


    /**
     * Prints the foundation release cascade section
     * (IKE-Network/ike-issues#402, #420).
     *
     * <p>When the releasing repository version-controls its own
     * {@code src/main/cascade/release-cascade.yaml} it is a cascade
     * member: this surfaces the downstream repos the release affects
     * — a preview in draft mode, a "what's next" footer in publish
     * mode. A repository with no such file (an ordinary consumer) or
     * an unreadable manifest is silently skipped — cascade reporting
     * is purely advisory and never fails or blocks a release.
     *
     * @param gitRoot the releasing repository's git root
     * @param draft   {@code true} for the draft preview, {@code false}
     *                for the post-publish footer
     */
    private void reportCascade(File gitRoot, boolean draft) {
        try {
            Optional<ProjectCascade> loaded = ProjectCascadeIo.load(
                    gitRoot.toPath().resolve(
                            ProjectCascadeIo.MANIFEST_RELATIVE_PATH));
            if (loaded.isEmpty()) {
                // No release-cascade.yaml — an ordinary consumer, not
                // a foundation cascade member. Nothing to report.
                return;
            }
            String repo = gitRoot.getName();
            List<String> lines = draft
                    ? CascadeReporter.draftPreview(loaded.get(), repo)
                    : CascadeReporter.publishFooter(loaded.get(), repo);
            getLog().info("");
            lines.forEach(getLog()::info);
        } catch (RuntimeException e) {
            getLog().warn("Release cascade report skipped: "
                    + e.getMessage());
        }
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
        GoalReportBuilder report = new GoalReportBuilder();
        report.raw("**Project:** " + projectId + "\n"
                + "**Mode:** " + (draft ? "draft (preview)" : "publish") + "\n"
                + "**Version:** " + oldVersion + " → " + releaseVersion + "\n"
                + "**Next version:** " + nextVersion + "\n"
                + "**Release branch:** " + releaseBranch + "\n"
                + "**Tag:** v" + releaseVersion + "\n"
                + "**Timestamp:** " + releaseTimestamp + "\n\n");

        String verb = draft ? "Would" : "Did";
        report.section("Local actions");
        StringBuilder local = new StringBuilder();
        local.append("1. ").append(verb)
                .append(" create branch `").append(releaseBranch).append("`\n");
        local.append("2. ").append(verb)
                .append(" set version ").append(oldVersion).append(" → ")
                .append(releaseVersion).append("\n");
        local.append("3. ").append(verb)
                .append(" stamp `project.build.outputTimestamp`\n");
        local.append("4. ").append(verb)
                .append(" resolve `${project.version}` in all POMs\n");
        local.append("5. ").append(verb).append(" run `mvnw clean verify -B`\n");
        local.append("6. ").append(verb)
                .append(" commit and tag `v").append(releaseVersion)
                .append("`\n");
        local.append("7. ").append(verb)
                .append(" merge `").append(releaseBranch).append("` to main\n");
        local.append("8. ").append(verb)
                .append(" bump to next version ").append(nextVersion)
                .append("\n\n");
        report.raw(local.toString());

        report.section("External actions");
        StringBuilder external = new StringBuilder();
        int step = 1;
        if (publishSite) {
            external.append(step++).append(". ").append(verb)
                    .append(" generate site\n");
        }
        external.append(step++).append(". ").append(verb)
                .append(" deploy to Nexus from tag `v")
                .append(releaseVersion).append("`")
                .append(deployAttemptSuffix(
                        nexusOutcome.attempts(), nexusDeployMaxAttempts))
                .append('\n');
        if (publishToCentral) {
            external.append(step++).append(". ").append(verb)
                    .append(" publish to Maven Central from tag `v")
                    .append(releaseVersion).append("`")
                    .append(centralOutcomeSuffix())
                    .append('\n');
        }
        if (publishSite) {
            external.append(step++).append(". ").append(verb)
                    .append(" force-push site to gh-pages on origin "
                            + "(serves at `https://ike.network/")
                    .append(projectId).append("/`)\n");
        }
        external.append(step++).append(". ").append(verb)
                .append(" push tag and main to origin\n");
        external.append(step).append(". ").append(verb)
                .append(" create GitHub Release\n");
        report.raw(external.toString());

        // Deploy details section (publish mode only — draft has no
        // cycle data to report). Always renders the Nexus line.
        // The Maven Central line renders only when publishToCentral
        // is set, with three possible outcomes (success / skip /
        // failure). IKE-Network/ike-issues#482.
        if (!draft) {
            report.section("Deploy details");
            StringBuilder deploy = new StringBuilder();
            deploy.append("- **Nexus:** ")
                    .append(nexusOutcome.succeeded()
                            ? "✅ succeeded on cycle "
                                    + nexusOutcome.attempts() + "/"
                                    + nexusDeployMaxAttempts
                            : skipNexusDeploy
                                    ? "⚠ skipped (ike.skipNexusDeploy=true)"
                                    : "❌ did not run")
                    .append('\n');
            if (publishToCentral) {
                deploy.append("- **Maven Central:** ");
                if (centralOutcome.asyncSpawned()) {
                    // Async path (#484) — outcome unknown at this
                    // point. Point the operator at the discovery
                    // surface (sentinel + log + status goal) rather
                    // than the (unknown) cycle count.
                    deploy.append("⏳ running async (#484) — track "
                                    + "with `mvn ")
                            .append(IkeGoal.CENTRAL_STATUS.qualified())
                            .append("`")
                            .append("\n  - Sentinel: `")
                            .append(centralOutcome.sentinelPath())
                            .append("`\n  - Log: `")
                            .append(centralOutcome.logPath())
                            .append('`');
                } else if (centralOutcome.succeeded()) {
                    deploy.append("✅ succeeded on cycle ")
                            .append(centralOutcome.attempts()).append("/")
                            .append(centralDeployMaxAttempts);
                } else if (centralOutcome.skipReason() != null) {
                    deploy.append("⚠ skipped — ")
                            .append(centralOutcome.skipReason());
                } else if (centralOutcome.attempts() > 0) {
                    deploy.append("❌ failed after ")
                            .append(centralOutcome.attempts()).append("/")
                            .append(centralDeployMaxAttempts)
                            .append(" cycles");
                    if (centralOutcome.failureSummary() != null) {
                        deploy.append(" — ")
                                .append(centralOutcome.failureSummary());
                    }
                    deploy.append("\n  Retry: `git checkout v")
                            .append(releaseVersion)
                            .append(" && mvn jreleaser:deploy`");
                } else {
                    deploy.append("⚠ did not run");
                }
                deploy.append('\n');
            }
            report.raw(deploy.toString());
        }

        return report.build();
    }

    /**
     * Render a {@code " (cycle N/M)"} suffix for the post-release
     * report, or empty when no cycles were tracked (draft mode).
     *
     * @param attempts retry cycles taken (0 = none)
     * @param max      configured max cycles
     * @return the suffix, possibly empty
     */
    private static String deployAttemptSuffix(int attempts, int max) {
        if (attempts <= 0) {
            return "";
        }
        return " (cycle " + attempts + "/" + max + ")";
    }

    /**
     * Render an outcome suffix for the Maven Central row in the
     * External-actions list. Distinguishes succeeded / skipped /
     * failed / pending-draft.
     *
     * @return the outcome suffix
     */
    private String centralOutcomeSuffix() {
        if (centralOutcome.asyncSpawned()) {
            return " (async — see Deploy details)";
        }
        if (centralOutcome.succeeded()) {
            return " (cycle " + centralOutcome.attempts() + "/"
                    + centralDeployMaxAttempts + ")";
        }
        if (centralOutcome.skipReason() != null) {
            return " — skipped (" + centralOutcome.skipReason() + ")";
        }
        if (centralOutcome.attempts() > 0) {
            return " — FAILED after " + centralOutcome.attempts() + "/"
                    + centralDeployMaxAttempts + " cycles";
        }
        return "";
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
     * Release-prep foundation bake (IKE-Network/ike-issues#414).
     *
     * <p>When the release being cut owns the scaffold manifest — i.e.
     * this is the {@code ike-tooling} release — refresh the manifest's
     * {@code foundation:} block to the latest released {@code ike-parent},
     * {@code ike-docs}, and {@code ike-platform} versions, so the
     * scaffold zip {@code ike-tooling} ships always carries a current
     * compatibility snapshot with no manual edit. A no-op for every
     * other project's release (no scaffold manifest present).
     *
     * <p>A pin newer than any resolvable GA, or one that cannot be
     * resolved at all, fails a publish (warns a draft): staleness or a
     * misconfigured remote must never be silently baked into the zip.
     *
     * @param gitRoot the release repository root
     * @param draft   {@code true} to report only; {@code false} to
     *                rewrite the manifest and commit it
     * @throws MojoException on a backward or unresolvable pin in
     *                       publish mode, or on an I/O failure
     */
    private void bakeFoundationSnapshot(File gitRoot, boolean draft)
            throws MojoException {
        File manifestFile = new File(gitRoot,
                "ike-build-standards/src/main/scaffold/scaffold-manifest.yaml");
        if (!manifestFile.isFile()) {
            // Not the ike-tooling release — nothing to bake.
            return;
        }

        String content;
        ScaffoldManifest manifest;
        try {
            content = Files.readString(manifestFile.toPath(),
                    StandardCharsets.UTF_8);
            manifest = ScaffoldManifestIo.read(manifestFile.toPath());
        } catch (IOException e) {
            throw new MojoException("Could not read scaffold manifest "
                    + manifestFile + ": " + e.getMessage(), e);
        }
        if (manifest.foundation() == null) {
            getLog().warn("Foundation bake: scaffold manifest has no "
                    + "foundation: block — skipping.");
            return;
        }

        List<FoundationBaker.Finding> findings;
        try {
            findings = FoundationBaker.assess(manifest.foundation(),
                    new SessionCandidateVersionResolver(getSession()));
        } catch (RuntimeException e) {
            String msg = "Foundation bake: could not resolve latest "
                    + "released versions — " + e.getMessage();
            if (publish) {
                throw new MojoException(msg, e);
            }
            getLog().warn(msg);
            return;
        }

        List<FoundationBaker.Finding> problems = new ArrayList<>();
        List<FoundationBaker.Finding> bumps = new ArrayList<>();
        for (FoundationBaker.Finding f : findings) {
            switch (f.status()) {
                case AHEAD -> bumps.add(f);
                case BEHIND, UNRESOLVED -> problems.add(f);
                case CURRENT -> { }
            }
        }

        if (!problems.isEmpty()) {
            StringBuilder msg = new StringBuilder(
                    "Foundation bake found pin(s) that cannot be baked:\n");
            for (FoundationBaker.Finding f : problems) {
                msg.append("  ").append(f.coordinate().label()).append(": ");
                if (f.status() == FoundationBaker.Status.UNRESOLVED) {
                    msg.append("no released version resolved (current pin ")
                            .append(f.current()).append(").");
                } else {
                    msg.append("pin ").append(f.current())
                            .append(" is newer than the latest released ")
                            .append(f.latest()).append(" — a backward bake.");
                }
                msg.append('\n');
            }
            msg.append("Verify the remote repository and the manifest "
                    + "foundation: block before releasing.");
            if (publish) {
                throw new MojoException(msg.toString());
            }
            getLog().warn(msg.toString());
        }

        if (bumps.isEmpty()) {
            getLog().info("Foundation bake: scaffold foundation: block "
                    + "already at the latest released versions.");
            return;
        }

        getLog().info("Foundation bake:");
        for (FoundationBaker.Finding f : bumps) {
            getLog().info("  " + (draft ? "→ " : "✓ ")
                    + f.coordinate().label() + ": "
                    + f.current() + " -> " + f.latest());
        }
        if (draft) {
            getLog().info("  [DRAFT] manifest not modified — publish would "
                    + "rewrite and commit scaffold-manifest.yaml.");
            return;
        }

        String updated = FoundationBaker.rewrite(content, findings);
        try {
            Files.writeString(manifestFile.toPath(), updated,
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new MojoException("Could not write baked scaffold "
                    + "manifest " + manifestFile + ": " + e.getMessage(), e);
        }
        ReleaseSupport.exec(gitRoot, getLog(), "git", "add",
                "ike-build-standards/src/main/scaffold/scaffold-manifest.yaml");
        ReleaseSupport.exec(gitRoot, getLog(), "git", "commit", "-m",
                "release: bake foundation snapshot to latest GA");
    }

    /**
     * Aligns this repository's upstream-cascade {@code ${X.version}}
     * properties to the latest released version of each upstream
     * (IKE-Network/ike-issues#419, #420).
     *
     * <p>Before a foundation repo is released it must carry current
     * upstream pins, or it ships a stale foundation. This reads the
     * repo's own {@code src/main/cascade/release-cascade.yaml} and, for
     * every {@code upstream} edge, resolves the latest released (GA)
     * version of that upstream and bumps the edge's
     * {@code version-property} when the POM is behind. A property is
     * only advanced, never lowered.
     *
     * <p>The cascade head (no upstream edges) and ordinary consumers
     * (no {@code release-cascade.yaml}) are no-ops. In draft mode the
     * alignment is reported but not applied; in publish mode the bumps
     * are written and committed before the release branch is cut, so a
     * plain single-repo {@code ike:release-publish} is correct on its
     * own.
     *
     * @param gitRoot the release repository root
     * @param draft   {@code true} to report only; {@code false} to
     *                rewrite the POM and commit
     * @throws MojoException on an unresolvable upstream or a missing
     *                       {@code version-property} in publish mode,
     *                       or on an I/O failure
     */
    private void alignUpstreamProperties(File gitRoot, boolean draft)
            throws MojoException {
        Optional<ProjectCascade> loaded = ProjectCascadeIo.load(
                gitRoot.toPath().resolve(
                        ProjectCascadeIo.MANIFEST_RELATIVE_PATH));
        if (loaded.isEmpty() || loaded.get().upstream().isEmpty()) {
            // Not a cascade member, or the cascade head — nothing
            // upstream to align.
            return;
        }

        File pomFile = new File(gitRoot, "pom.xml");
        String content;
        try {
            content = Files.readString(pomFile.toPath(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new MojoException("Could not read " + pomFile
                    + " for upstream cascade alignment: "
                    + e.getMessage(), e);
        }

        CandidateVersionResolver resolver =
                new SessionCandidateVersionResolver(getSession());
        List<String> bumps = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        String updated = content;

        for (CascadeEdge up : loaded.get().upstream()) {
            // PARENT-kind edges rewrite the <parent><version> block
            // directly; property-kind edges rewrite the ${G·A} property
            // that pins the upstream. The site of the value (the read
            // and the write) differs by kind; the candidate-resolution
            // and "is the pin stale" logic is the same for both.
            boolean parentEdge = up.kind() == EdgeKind.PARENT;
            String property = up.versionProperty();
            String displaySite = parentEdge
                    ? "<parent>" + up.ga() + "</parent>"
                    : "<" + property + ">";
            String current = parentEdge
                    ? PomRewriter.readParentVersion(content,
                            up.groupId(), up.artifactId()).orElse(null)
                    : ReleaseSupport.readPomProperty(pomFile, property);
            if (current == null) {
                problems.add(up.ga() + ": POM has no " + displaySite
                        + ".");
                continue;
            }
            if (current.contains("${")) {
                continue;
            }
            String latest;
            try {
                List<String> candidates = resolver.resolveCandidates(
                        up.groupId(), up.artifactId(), null);
                latest = candidates.isEmpty() ? null
                        : candidates.get(candidates.size() - 1);
            } catch (RuntimeException e) {
                problems.add(up.ga() + ": could not resolve latest"
                        + " release — " + e.getMessage());
                continue;
            }
            if (latest == null) {
                problems.add(up.ga()
                        + ": no released version resolved.");
                continue;
            }
            if (MavenVersionComparator.INSTANCE
                    .compare(latest, current) <= 0) {
                continue;
            }
            String after = parentEdge
                    ? PomRewriter.updateParentVersion(updated,
                            up.groupId(), up.artifactId(), latest)
                    : PomRewriter.updateProperty(updated, property,
                            latest);
            if (!after.equals(updated)) {
                updated = after;
                bumps.add(displaySite + ": " + current + " -> "
                        + latest);
            }
        }

        if (!problems.isEmpty()) {
            StringBuilder msg = new StringBuilder("Upstream cascade"
                    + " alignment found unresolvable upstream pin(s):\n");
            for (String p : problems) {
                msg.append("  ").append(p).append('\n');
            }
            msg.append("Verify the remote repository and the upstream"
                    + " edges in release-cascade.yaml before releasing.");
            if (!draft) {
                throw new MojoException(msg.toString());
            }
            getLog().warn(msg.toString());
        }

        if (bumps.isEmpty()) {
            getLog().info("Upstream cascade alignment: ${X.version}"
                    + " pins already at the latest released versions.");
            return;
        }

        getLog().info("Upstream cascade alignment:");
        for (String b : bumps) {
            getLog().info("  " + (draft ? "→ " : "✓ ") + b);
        }
        if (draft) {
            getLog().info("  [DRAFT] pom.xml not modified — publish"
                    + " would rewrite and commit it.");
            return;
        }

        try {
            Files.writeString(pomFile.toPath(), updated,
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new MojoException("Could not write aligned " + pomFile
                    + ": " + e.getMessage(), e);
        }
        ReleaseSupport.exec(gitRoot, getLog(), "git", "add", "pom.xml");
        ReleaseSupport.exec(gitRoot, getLog(), "git", "commit", "-m",
                "release: align upstream cascade versions");
    }

    /**
     * Verify all external dependencies before starting the release.
     *
     * <p>Each check is non-destructive and fast — failures here happen
     * in seconds instead of after a 10-minute build cycle. Every check
     * runs to completion and records into one of two buckets rather
     * than failing fast, so a single run logs the complete picture of
     * everything wrong (IKE-Network/ike-issues#428):
     * <ul>
     *   <li><b>errors</b> — git-push authentication, {@code gh} push
     *       permission on {@code issueRepo}, a missing Maven wrapper.
     *       Always abort the release; never ignorable.</li>
     *   <li><b>warnings</b> — {@code gh} CLI unavailable, a missing
     *       {@code pending-release} label or release milestone,
     *       commits with no issue trailer. Abort the release too,
     *       unless {@code -Dike.release.ignoreWarnings=true}.</li>
     * </ul>
     *
     * <p>Checks: git-push auth, {@code gh} CLI availability, {@code gh}
     * write permission on {@code issueRepo} (#392), {@code pending-release}
     * label existence (#392), commit trailer compliance (#392),
     * release-milestone existence (#392), and Maven wrapper presence.
     * Only invoked for a publish.
     *
     * @param gitRoot        the release repository root
     * @param hasOrigin      whether an {@code origin} remote is configured
     * @param projectId      the project artifactId, for the milestone name
     * @param releaseVersion the version being released
     * @throws MojoException if any preflight error is found, or any
     *                       warning is found and {@code ignoreWarnings}
     *                       is not set
     */
    private void preflightChecks(File gitRoot, boolean hasOrigin,
                                  String projectId, String releaseVersion)
            throws MojoException {
        getLog().info("");
        getLog().info("PREFLIGHT CHECKS");
        List<String> errors = new java.util.ArrayList<>();
        List<String> warnings = new java.util.ArrayList<>();

        // 1. Git push auth — draft push (sends nothing, tests auth)
        if (hasOrigin) {
            try {
                ReleaseSupport.execCapture(gitRoot,
                        "git", "push", "--dry-run", "origin", "main");
                getLog().info("  Git push:    authenticated  ✓");
            } catch (Exception e) {
                errors.add("Cannot push to origin — fix authentication"
                        + " before releasing. Error: " + e.getMessage());
                getLog().error("  Git push:    authentication failed  ✗");
            }
        } else {
            getLog().info("  Git push:    no origin remote (local-only release)");
        }

        // 2. gh CLI — installed and authenticated?
        boolean ghAvailable = false;
        if (hasOrigin) {
            try {
                ReleaseSupport.execCapture(gitRoot, "gh", "auth", "status");
                getLog().info("  gh CLI:      authenticated  ✓");
                ghAvailable = true;
            } catch (Exception e) {
                warnings.add("gh CLI not available or not authenticated — "
                        + "GitHub Release will be skipped. "
                        + "Run: gh auth login");
                getLog().warn("  gh CLI:      not available (GitHub Release "
                        + "will be skipped)");
            }
        }

        // 3. gh write permission on issueRepo (#392) — an error.
        // Required for closeMilestone and removePendingReleaseLabels.
        if (ghAvailable && issueRepo != null && !issueRepo.isBlank()) {
            try {
                String pushPerm = ReleaseSupport.execCapture(gitRoot,
                        "gh", "api", "/repos/" + issueRepo,
                        "--jq", ".permissions.push");
                if ("true".equals(pushPerm.trim())) {
                    getLog().info("  gh perms:    push on "
                            + issueRepo + "  ✓");
                } else {
                    errors.add("gh token lacks push permission on "
                            + issueRepo + " — required for milestone"
                            + " close and pending-release label removal."
                            + " Re-authenticate with repo scope:"
                            + " gh auth refresh -s repo");
                    getLog().error("  gh perms:    no push on "
                            + issueRepo + "  ✗");
                }
            } catch (Exception e) {
                warnings.add("Could not verify gh permissions on "
                        + issueRepo + ": " + e.getMessage());
            }
        }

        // 4. pending-release label exists on issueRepo (#392) — warn.
        if (ghAvailable && issueRepo != null && !issueRepo.isBlank()) {
            try {
                ReleaseSupport.execCapture(gitRoot, "gh", "api",
                        "/repos/" + issueRepo + "/labels/pending-release");
                getLog().info("  pending-rel label on " + issueRepo + "  ✓");
            } catch (Exception e) {
                warnings.add("Label 'pending-release' missing on "
                        + issueRepo + " — label removal will be a no-op. "
                        + "Create it: gh label create pending-release "
                        + "--repo " + issueRepo
                        + " --description \"Code complete; awaiting next release\"");
                getLog().warn("  pending-rel label: missing on " + issueRepo);
            }
        }

        // 5. Trailer compliance for commits in release range (#392) — warn.
        if (hasOrigin) {
            List<String> nonCompliant =
                    findCommitsWithoutIssueTrailer(gitRoot);
            if (nonCompliant.isEmpty()) {
                getLog().info("  Trailer compliance: all commits ✓");
            } else {
                StringBuilder msg = new StringBuilder(nonCompliant.size()
                        + " commit(s) in release range have no issue trailer "
                        + "(IKE-COMMITS.md):");
                for (String line : nonCompliant) {
                    msg.append("\n      ").append(line);
                }
                msg.append("\n  Add Fixes/Refs <owner>/<repo>#N to comply.");
                warnings.add(msg.toString());
                getLog().warn("  Trailer compliance: " + nonCompliant.size()
                        + " commit(s) without issue trailer");
            }
        }

        // 6. Milestone for releaseVersion exists on issueRepo (#392) — warn.
        if (ghAvailable && issueRepo != null && !issueRepo.isBlank()
                && releaseVersion != null && !releaseVersion.isBlank()) {
            String milestoneName = projectId + " v" + releaseVersion;
            try {
                String titles = ReleaseSupport.execCapture(gitRoot, "gh", "api",
                        "/repos/" + issueRepo + "/milestones?state=open&per_page=100",
                        "--jq", ".[].title");
                boolean found = false;
                for (String title : titles.split("\n")) {
                    if (milestoneName.equals(title.trim())) {
                        found = true;
                        break;
                    }
                }
                if (found) {
                    getLog().info("  Milestone:   " + milestoneName + "  ✓");
                } else {
                    warnings.add("Milestone \"" + milestoneName
                            + "\" not found on " + issueRepo
                            + " — release will use auto-generated notes. "
                            + "Create it: gh api /repos/" + issueRepo
                            + "/milestones -f title='" + milestoneName + "'");
                    getLog().warn("  Milestone:   " + milestoneName
                            + " missing (auto-notes fallback)");
                }
            } catch (Exception e) {
                warnings.add("Could not check milestone existence: "
                        + e.getMessage());
            }
        }

        // 7. Maven wrapper
        try {
            ReleaseSupport.resolveMavenWrapper(gitRoot, getLog());
            getLog().info("  Maven:       wrapper found  ✓");
        } catch (Exception e) {
            errors.add("Maven wrapper (mvnw) not found."
                    + " Run: mvn wrapper:wrapper");
            getLog().error("  Maven:       wrapper not found  ✗");
        }

        // Report the complete preflight picture, then decide (#428).
        // Every check above ran to completion and recorded into
        // `errors` or `warnings` rather than failing fast, so this one
        // pass logs everything wrong. Errors always abort the release;
        // warnings abort too unless -Dike.release.ignoreWarnings=true.
        if (!errors.isEmpty() || !warnings.isEmpty()) {
            getLog().info("");
            for (String err : errors) {
                getLog().error("  ✗ " + err);
            }
            for (String w : warnings) {
                getLog().warn("  ⚠ " + w);
            }
            getLog().info("");
        }

        if (!errors.isEmpty()) {
            throw new MojoException("Release preflight found "
                    + errors.size() + " error(s)"
                    + (warnings.isEmpty() ? ""
                            : " and " + warnings.size() + " warning(s)")
                    + " — see above. Errors must be resolved before"
                    + " releasing; they are never ignorable.");
        }
        if (!warnings.isEmpty()) {
            if (ignoreWarnings) {
                getLog().warn("  Proceeding past " + warnings.size()
                        + " warning(s) (ike.release.ignoreWarnings=true).");
            } else {
                throw new MojoException("Release preflight found "
                        + warnings.size() + " warning(s) — see above."
                        + " Resolve them, or pass"
                        + " -Dike.release.ignoreWarnings=true to release"
                        + " anyway.");
            }
        }
        getLog().info("");
    }

    /**
     * Release-cadence commit subjects — the tool-generated bookkeeping
     * commits the release flow itself produces ({@code release: …},
     * {@code post-release: …}, the {@code merge: release …} commit,
     * {@code site: publish …}). They legitimately carry no issue
     * trailer and must be exempt from the trailer-compliance check,
     * or every release would fail its own preflight on the previous
     * cycle's bookkeeping (IKE-Network/ike-issues#428).
     */
    private static final Pattern RELEASE_CADENCE = Pattern.compile(
            "^(release: .+"
                    + "|post-release: .+"
                    + "|merge: release .+"
                    + "|site: publish .+)$");

    /**
     * Find commits in {@code <previous-tag>..HEAD} whose body contains
     * no IKE-COMMITS.md issue trailer ({@code Fixes}, {@code Closes},
     * {@code Resolves}, {@code Refs} and grammatical variants).
     *
     * <p>Uses NUL-delimited git-log output to handle commit messages
     * containing arbitrary characters. Returns short SHA + subject for
     * each non-compliant commit. Release-cadence commits ({@link
     * #RELEASE_CADENCE}) are exempt — they are tool-generated and
     * carry no issue trailer by design.
     *
     * <p>Returns an empty list (not an error) if the previous tag
     * cannot be resolved — typical for first-release scenarios.
     *
     * @param gitRoot the git working tree
     * @return list of "short-sha subject" strings, empty if all comply
     */
    private List<String> findCommitsWithoutIssueTrailer(File gitRoot) {
        try {
            String previousTag;
            try {
                previousTag = ReleaseSupport.execCapture(gitRoot,
                        "git", "describe", "--tags", "--abbrev=0", "HEAD");
            } catch (Exception e) {
                getLog().debug("  No previous tag — skipping trailer compliance");
                return List.of();
            }
            // Per-commit body separated by NUL byte (-z) so embedded
            // newlines don't confuse the parser.
            String log = ReleaseSupport.execCapture(gitRoot, "git", "log",
                    "-z", "--format=%h%x00%B", previousTag + "..HEAD");
            if (log.isBlank()) {
                return List.of();
            }
            List<String> nonCompliant = new java.util.ArrayList<>();
            // Stream is "<sha>\0<body>\0<sha>\0<body>\0..." after -z.
            // Splitting on NUL gives alternating sha/body pairs.
            String[] records = log.split("\u0000");
            for (int i = 0; i + 1 < records.length; i += 2) {
                String sha = records[i].trim();
                String body = records[i + 1];
                if (!ReleaseNotesSupport.hasAnyIssueTrailer(body)) {
                    String firstLine = body.contains("\n")
                            ? body.substring(0, body.indexOf('\n'))
                            : body;
                    String subject = firstLine.trim();
                    if (RELEASE_CADENCE.matcher(subject).matches()) {
                        // Tool-generated bookkeeping — no trailer by design.
                        continue;
                    }
                    nonCompliant.add(sha + " " + subject);
                }
            }
            return nonCompliant;
        } catch (Exception e) {
            getLog().debug("  Trailer compliance check failed: "
                    + e.getMessage());
            return List.of();
        }
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
            // -q stripped the [WARNING] prefix the grep below keys on,
            // letting javadoc "reference not found" warnings slip through
            // preflight (see ike-issues #178). -B keeps output non-interactive.
            Process proc = new ProcessBuilder(
                    "mvn", "-B",
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
