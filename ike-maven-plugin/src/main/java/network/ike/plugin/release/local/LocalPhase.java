package network.ike.plugin.release.local;

import network.ike.plugin.ReleaseSupport;
import network.ike.plugin.SnapshotScanner;
import network.ike.plugin.release.ReleaseContext;
import org.apache.maven.api.plugin.MojoException;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The local-only release phase — B13 through B19 of the
 * {@code ReleaseDraftMojo} block audit:
 *
 * <ol>
 *   <li>B13 — cut {@code release/<version>} branch from main</li>
 *   <li>B14 — replace project-version refs, scan for surviving SNAPSHOTs</li>
 *   <li>B15 — first {@code mvn clean install} (Maven 4 consumer POM flatten + local install)</li>
 *   <li>B16 — pre-flight site build (catches javadoc errors before tag)</li>
 *   <li>B17 — release commit on {@code release/<version>}</li>
 *   <li>B18 — {@code git tag v<version>} — the irreversibility boundary</li>
 *   <li>B19a — restore {@code ${project.version}} references, restore-commit</li>
 *   <li>B19b — merge {@code release/<version>} to {@code main}</li>
 *   <li>B19c — post-release bump to next {@code -SNAPSHOT}, install, commit, delete release branch</li>
 * </ol>
 *
 * <p>Everything in this phase is local and reversible — the moment B18
 * runs, the release tag is in place, but nothing externally visible
 * has happened yet. External deploys run from the tagged commit
 * inside a {@code WorktreeGuard} (see {@code ReleaseDraftMojo}).
 *
 * <p>Carved out of {@code ReleaseDraftMojo.runGoal()} during the
 * Phase 4 Commit 4 (IKE-Network/ike-issues#489).
 */
public final class LocalPhase {

    private final ReleaseContext ctx;

    /**
     * Creates a new local phase bound to the given context.
     *
     * @param ctx the per-invocation release context
     */
    public LocalPhase(ReleaseContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Executes the local release phase.
     *
     * <p>On resume ({@code input.resuming()} true), branch creation
     * and version-setting (B13, B14) are skipped — the previous
     * attempt left the {@code release/<version>} branch and the
     * version-mutated POMs in place. The remaining steps (install,
     * site, commit, tag, restore, merge, post-bump) run as usual.
     *
     * @param input the prep-stage outputs needed for the local phase
     * @return a {@link LocalOutcome} summarizing which sub-steps ran
     * @throws MojoException on any subprocess failure or surviving SNAPSHOT version
     */
    public LocalOutcome execute(LocalInput input) throws MojoException {
        String releaseVersion = ctx.request().releaseVersion();
        String nextVersion = ctx.request().nextVersion();
        String releaseBranch = "release/" + releaseVersion;
        File rootPom = new File(ctx.gitRoot(), "pom.xml");

        List<File> resolvedPoms = cutBranchAndSetVersion(
                input, releaseVersion, releaseBranch, rootPom);

        firstInstall();
        preflightSite(input.oldVersion());
        commitAndTag(resolvedPoms, releaseVersion);
        restoreReferences();
        mergeToMain(releaseBranch, releaseVersion);
        postBump(rootPom, nextVersion, releaseBranch);

        return new LocalOutcome("v" + releaseVersion, true, true, true);
    }

    /**
     * B13 + B14 — cuts the {@code release/<version>} branch from main,
     * sets the release version in the root POM, stamps the
     * reproducible-build timestamp, then resolves {@code ${project.version}}
     * references across all POMs and scans for any surviving
     * {@code -SNAPSHOT} versions (defense in depth for #175 / #177).
     *
     * <p>Skipped on resume; returns an empty list of resolved POMs in
     * that case.
     */
    private List<File> cutBranchAndSetVersion(LocalInput input,
                                              String releaseVersion,
                                              String releaseBranch,
                                              File rootPom) {
        File gitRoot = ctx.gitRoot();
        if (input.resuming()) {
            ctx.log().info("Skipping version set (already " + releaseVersion + ")");
            return List.of();
        }
        ReleaseSupport.exec(gitRoot, ctx.log(),
                "git", "checkout", "-b", releaseBranch);

        ctx.log().info("Setting version: " + input.oldVersion()
                + " -> " + releaseVersion);
        ReleaseSupport.setPomVersion(rootPom, input.oldVersion(), releaseVersion);

        ctx.log().info("Stamping project.build.outputTimestamp: "
                + input.releaseTimestamp());
        ReleaseSupport.stampOutputTimestamp(rootPom, input.releaseTimestamp(), ctx.log());

        // WORKAROUND: Maven 4 consumer POM doesn't resolve ${project.version}
        // in <build><plugins>, <pluginManagement>, or <dependencyManagement>.
        ctx.log().info("Resolving ${project.version} references:");
        List<File> resolvedPoms =
                ReleaseSupport.replaceProjectVersionRefs(gitRoot, releaseVersion, ctx.log());

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
        return resolvedPoms;
    }

    /**
     * B15 — runs the first {@code mvn clean install} from the release
     * branch.
     *
     * <p>Uses {@code install} rather than {@code verify} so reactor
     * siblings with BOM imports can resolve classified dependencies
     * (e.g., {@code ike-build-standards:zip:claude}). The release
     * version has never been installed; {@code verify} alone fails on
     * inter-module resolution. {@code install} puts artifacts in the
     * local repo for sibling resolution.
     *
     * <p>Skipped when {@code skipVerify} is set on the request.
     */
    private void firstInstall() {
        if (ctx.request().skipVerify()) {
            ctx.log().info("Skipping verify (-DskipVerify=true)");
            return;
        }
        ReleaseSupport.exec(ctx.gitRoot(), ctx.log(),
                ctx.mvnw().getAbsolutePath(), "clean", "install", "-B", "-T", "1");
    }

    /**
     * B16 — pre-flight site build (catches javadoc errors before any
     * commits/tags).
     *
     * <p>{@code -T 1} overrides any project-level parallelism: the
     * maven-site-plugin is not {@code @ThreadSafe} and emits warnings
     * in parallel sessions. {@code -N} (non-recursive) when releasing
     * an aggregator whose subproject sites would otherwise collide at
     * the staging root (ike-issues#356).
     *
     * <h4>X-SNAPSHOT bootstrap (2 of 2) — ike-issues#370</h4>
     *
     * <p>Every {@code mvn site} / {@code mvn site:stage} invocation in
     * this mojo passes {@code -Drelease.bootstrap.version=<oldVersion>}.
     * {@code oldVersion} is the pre-release pom version (i.e.,
     * {@code X-SNAPSHOT}, where X is the version about to be released).
     *
     * <p>The property activates the {@code releaseSelfSite} profile in
     * any reactor-root pom that declares it (currently just
     * {@code ike-tooling} itself, which has the cycle problem). Inside
     * that profile, {@code ike-maven-plugin} is bound at
     * {@code <version>${release.bootstrap.version}</version>} — i.e.,
     * at {@code X-SNAPSHOT}, which is a DIFFERENT GAV than the reactor
     * submodules (set to X by the version-set step above). Different
     * GAV → no graph edge to a submodule → no reactor cycle. Maven
     * flags the cycle at reactor evaluation time, so the indirection
     * has to live in the pom; we just supply the property value.
     *
     * <p>Why {@code X-SNAPSHOT} is guaranteed in {@code ~/.m2}: the
     * first {@code mvn clean install} above runs against {@code X}
     * (the release version), but the prior pre-release {@code install}
     * (run by the cascade orchestrator or manual setup) put
     * {@code X-SNAPSHOT} in the local repo. Subsequent site invocations
     * resolve the plugin descriptor from there.
     *
     * <p>No-op for projects that do not declare the releaseSelfSite
     * profile — setting a property Maven does not see has no effect,
     * so this is safe to pass unconditionally.
     *
     * <p>THE OTHER HALF OF THIS PATTERN — see
     * {@code ike-tooling/pom.xml} (search "X-SNAPSHOT bootstrap (1 of 2)")
     * for the profile declaration that consumes
     * {@code ${release.bootstrap.version}}.
     *
     * <p>Note: only {@code mvn site} / {@code mvn site:stage}
     * invocations pass the property. Other release-flow {@code mvn}
     * calls (verify, deploy, site-publish, etc.) stay outside the
     * profile and resolve plugin coords via pluginManagement — the
     * standard self-host pattern, which works because pluginManagement
     * does not create reactor edges the way live {@code <plugins>} does.
     */
    private void preflightSite(String oldVersion) {
        if (!ctx.request().publishSite()) {
            return;
        }
        ctx.log().info("Building site (pre-flight check)...");
        List<String> siteArgs = new ArrayList<>();
        siteArgs.add(ctx.mvnw().getAbsolutePath());
        siteArgs.add("site");
        siteArgs.add("site:stage");
        siteArgs.add("-B");
        siteArgs.add("-T");
        siteArgs.add("1");
        siteArgs.add("-Drelease.bootstrap.version=" + oldVersion);
        if (ctx.request().nonRecursiveSite()) {
            siteArgs.add("-N");
        }
        ReleaseSupport.exec(ctx.gitRoot(), ctx.log(),
                siteArgs.toArray(new String[0]));
    }

    /**
     * B17 + B18 — release commit on {@code release/<version>}, then
     * {@code git tag -a v<version>}. The tag creation is the
     * irreversibility boundary of the local phase.
     */
    private void commitAndTag(List<File> resolvedPoms, String releaseVersion) {
        File gitRoot = ctx.gitRoot();
        ReleaseSupport.exec(gitRoot, ctx.log(), "git", "add", "pom.xml");
        ReleaseSupport.gitAddFiles(gitRoot, ctx.log(), resolvedPoms);
        ReleaseSupport.exec(gitRoot, ctx.log(),
                "git", "commit", "-m",
                "release: set version to " + releaseVersion);

        ReleaseSupport.exec(gitRoot, ctx.log(),
                "git", "tag", "-a", "v" + releaseVersion,
                "-m", "Release " + releaseVersion);
    }

    /**
     * B19a — restores {@code ${project.version}} references that
     * B14 substituted, then commits the restore on the release
     * branch. The tag created by B18 stays on the release commit
     * (above this restore-commit), not on the restored commit.
     */
    private void restoreReferences() {
        File gitRoot = ctx.gitRoot();
        ctx.log().info("Restoring ${project.version} references:");
        List<File> restoredPoms = ReleaseSupport.restoreBackups(gitRoot, ctx.log());
        if (!restoredPoms.isEmpty()) {
            ReleaseSupport.gitAddFiles(gitRoot, ctx.log(), restoredPoms);
            ReleaseSupport.exec(gitRoot, ctx.log(),
                    "git", "commit", "-m",
                    "release: restore ${project.version} references");
        }
    }

    /**
     * B19b — checks out {@code main} and merges
     * {@code release/<version>} with a no-fast-forward merge commit.
     */
    private void mergeToMain(String releaseBranch, String releaseVersion) {
        File gitRoot = ctx.gitRoot();
        ReleaseSupport.exec(gitRoot, ctx.log(), "git", "checkout", "main");
        ReleaseSupport.exec(gitRoot, ctx.log(),
                "git", "merge", "--no-ff", releaseBranch,
                "-m", "merge: release " + releaseVersion);
    }

    /**
     * B19c — bumps the POM version to the next {@code -SNAPSHOT},
     * runs {@code mvn clean install} on the post-bump tree (seeds
     * {@code ~/.m2} for self-hosting reactors per #486), commits,
     * and deletes the now-merged release branch.
     */
    private void postBump(File rootPom, String nextVersion, String releaseBranch) {
        File gitRoot = ctx.gitRoot();
        ctx.log().info("");
        ctx.log().info("Bumping to next version: " + nextVersion);

        // Re-read version after merge (it's the release version on main now)
        String currentVersion = ReleaseSupport.readPomVersion(rootPom);
        ReleaseSupport.setPomVersion(rootPom, currentVersion, nextVersion);

        // Verify AND install the new SNAPSHOT (IKE-Network/ike-issues#486).
        // `install` (not just `verify`) puts the post-bump -SNAPSHOT in
        // the local repo so a self-hosting repo — whose POM pins
        // ike-maven-plugin to ${project.version} — can run the next
        // ike:* goal (or an ike:release-cascade walk to the next
        // member) without a manual `mvn install` first.
        ReleaseSupport.exec(gitRoot, ctx.log(),
                ctx.mvnw().getAbsolutePath(), "clean", "install", "-B", "-T", "1");

        ReleaseSupport.exec(gitRoot, ctx.log(), "git", "add", "pom.xml");
        ReleaseSupport.exec(gitRoot, ctx.log(),
                "git", "commit", "-m",
                "post-release: bump to " + nextVersion);

        ReleaseSupport.exec(gitRoot, ctx.log(),
                "git", "branch", "-d", releaseBranch);
    }
}
