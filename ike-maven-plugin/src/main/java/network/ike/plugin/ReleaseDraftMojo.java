package network.ike.plugin;

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
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
            getLog().info("[DRAFT] Would deploy to Nexus from tag v" +
                    releaseVersion + " (critical)");
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

        // Track gh-pages publish outcome separately from Nexus deploy.
        // Used to gate the "GitHub Pages: ..." line in the release-
        // complete summary so the log does not falsely claim success
        // when the publish actually failed. ike-issues#329.
        boolean ghPagesPublished = !publishSite;  // skipped == "no failure"
        try {
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
                    SiteDeployResult result = deploySiteAndPublish(gitRoot,
                            mvnw, projectId, releaseVersion);
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
                            + "landing page (#367; via ike:site-publish "
                            + "after #398)...");
                    try {
                        // #398: site convergence — registration is the
                        // LandingPageRegistrationReconciler dimension of
                        // ike:site-publish. The site itself was already
                        // pushed to gh-pages above, so opt out of the
                        // DeployedSiteReconciler with -DupdateSite=false.
                        ReleaseSupport.exec(gitRoot, getLog(),
                                mvnw.getAbsolutePath(),
                                "ike:site-publish",
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

            // ── Nexus deploy (critical — the actual release) ─────────
            // clean deploy: fresh build ensures artifact integrity.
            // Site was already deployed above (before clean wipes staging).
            getLog().info("Deploying to Nexus...");
            ReleaseSupport.exec(gitRoot, getLog(),
                    mvnw.getAbsolutePath(), "clean", "deploy", "-B", "-T", "1",
                    "-P", "release,signArtifacts");
        } finally {
            // Always return to main, even if deploy fails.
            //
            // Stash any mid-flight worktree changes first (#373). By
            // this point the release has shipped: Nexus deploy +
            // gh-pages + org-site register are all done. The only
            // remaining work is `git checkout main`, push tag + main,
            // and the GitHub Release. If something has written to the
            // worktree mid-flight (an operator edit, a stray tool
            // output), `git checkout main` fails with
            //
            //   "Your local changes ... would be overwritten by checkout"
            //
            // — and the housekeeping never runs. Recovery is mechanical
            // but manual. Pre-empt by stashing foreign worktree
            // changes; the operator gets them back with `git stash
            // pop` after the release reports complete.
            //
            // Release-flow's own commits ran before this block (set-
            // version → tag → restore-project.version → merge → bump-
            // to-next-SNAPSHOT). So anything captured by the stash is
            // strictly foreign — by construction.
            stashForeignWorktreeChanges(gitRoot, releaseVersion);
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

        // Pre-#304: this block called cleanRemoteSiteDir to ssh-delete
        // the main-branch snapshot mirror on scpexe://proxy. With the
        // scpexe path retired, there's no remote dir to clean.

        // VCS state file now managed by ws:release for workspace-level
        // releases. Single-repo ike:release does not write VCS state.

        getLog().info("");
        getLog().info("Release " + releaseVersion + " complete.");
        getLog().info("  Tagged: v" + releaseVersion);
        getLog().info("  Deployed to Nexus");
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
                getLog().warn("    Retry: mvn ike:site-publish "
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
                .append(releaseVersion).append("`\n");
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

        return report.build();
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
            String property = up.versionProperty();
            String current = ReleaseSupport.readPomProperty(
                    pomFile, property);
            if (current == null) {
                problems.add(up.ga() + ": POM has no <" + property
                        + "> property.");
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
            String after = PomRewriter.updateProperty(
                    updated, property, latest);
            if (!after.equals(updated)) {
                updated = after;
                bumps.add("<" + property + ">: " + current
                        + " -> " + latest);
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
     * <p>Each check is non-destructive and fast. Failures here happen
     * in seconds instead of after a 10-minute build cycle.
     *
     * <p>Checks:
     * <ol>
     *   <li>Git push — can we authenticate to the remote?</li>
     *   <li>gh CLI — is it installed and authenticated?</li>
     *   <li>gh write permission on {@code issueRepo} — required by
     *       milestone close and {@code pending-release} label removal
     *       (#392). Fail-fast: silent permission failures mid-release
     *       leak label state.</li>
     *   <li>{@code pending-release} label exists on {@code issueRepo}
     *       (#392). Warn — label removal becomes a no-op for that repo
     *       if missing.</li>
     *   <li>Trailer compliance for commits in the release range
     *       (#392). Per IKE-COMMITS.md every commit must reference an
     *       issue via {@code Fixes}/{@code Closes}/{@code Resolves}/
     *       {@code Refs} trailer. Warn — promote to fail-fast after
     *       one release cycle of adoption.</li>
     *   <li>Milestone {@code <projectId> v<version>} exists (#392).
     *       Warn — release falls back to auto-generated notes if
     *       missing.</li>
     *   <li>Maven wrapper — is it present and executable?</li>
     * </ol>
     */
    private void preflightChecks(File gitRoot, boolean hasOrigin,
                                  String projectId, String releaseVersion)
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

        // 3. gh write permission on issueRepo (#392) — fail-fast.
        // Required for closeMilestone and removePendingReleaseLabels.
        if (ghAvailable && issueRepo != null && !issueRepo.isBlank()) {
            try {
                String pushPerm = ReleaseSupport.execCapture(gitRoot,
                        "gh", "api", "/repos/" + issueRepo,
                        "--jq", ".permissions.push");
                if (!"true".equals(pushPerm.trim())) {
                    throw new MojoException(
                            "gh token lacks push permission on " + issueRepo
                                    + ". Required for milestone close and "
                                    + "pending-release label removal.\n"
                                    + "  Re-authenticate with repo scope: "
                                    + "gh auth refresh -s repo");
                }
                getLog().info("  gh perms:    push on " + issueRepo + "  ✓");
            } catch (MojoException e) {
                // Re-throw scope failure; swallow other gh errors as warning
                if (e.getMessage() != null
                        && e.getMessage().startsWith("gh token lacks")) {
                    throw e;
                }
                warnings.add("Could not verify gh permissions on " + issueRepo
                        + ": " + e.getMessage());
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
            throw new MojoException(
                    "Maven wrapper (mvnw) not found. Run: mvn wrapper:wrapper");
        }

        if (!warnings.isEmpty()) {
            getLog().info("");
            for (String w : warnings) {
                getLog().warn("  ⚠ " + w);
            }
            getLog().info("");

            // Batch mode is non-interactive — log the warnings
            // and continue. Interactive mode asks the user to
            // confirm, routed through IkePrompter (ike-issues#385).
            if (!getPrompter().isInteractive()) {
                getLog().warn("  Batch mode: proceeding with "
                        + warnings.size() + " warning(s).");
            } else if (!getPrompter().confirm(
                    "  Continue with " + warnings.size() + " warning(s)?",
                    false)) {
                throw new MojoException(
                        "Release aborted. Resolve warnings and retry.");
            }
        }
        getLog().info("");
    }

    /**
     * Find commits in {@code <previous-tag>..HEAD} whose body contains
     * no IKE-COMMITS.md issue trailer ({@code Fixes}, {@code Closes},
     * {@code Resolves}, {@code Refs} and grammatical variants).
     *
     * <p>Uses NUL-delimited git-log output to handle commit messages
     * containing arbitrary characters. Returns short SHA + subject for
     * each non-compliant commit.
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
                    nonCompliant.add(sha + " " + firstLine.trim());
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
    private SiteDeployResult deploySiteAndPublish(File gitRoot, File mvnw,
                                          String projectId, String version)
            throws MojoException {
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
                            + "mvn ike:site-publish "
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
    private void stashForeignWorktreeChanges(File gitRoot,
                                              String releaseVersion) {
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

        // Remove pending-release label from issues resolved in this
        // release range. Runs regardless of milestone presence: cross-
        // org Fixes/Closes/Resolves trailers reach issues that won't
        // be in our milestone. Non-fatal.
        try {
            ReleaseNotesSupport.removePendingReleaseLabels(
                    gitRoot, null, "v" + version, issueRepo, getLog());
        } catch (Exception e) {
            getLog().warn("Could not remove pending-release labels "
                    + "(release succeeded): " + e.getMessage());
        }
    }
}
