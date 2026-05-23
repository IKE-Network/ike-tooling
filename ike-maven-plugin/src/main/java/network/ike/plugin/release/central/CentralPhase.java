package network.ike.plugin.release.central;

import network.ike.plugin.ReleaseSupport;
import network.ike.plugin.release.ReleaseContext;
import network.ike.plugin.release.RetrySchedule;
import org.apache.maven.api.plugin.MojoException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The Maven Central deploy phase of the release pipeline (sync path).
 *
 * <p>Stages signed artifacts to a local {@code staging-deploy}
 * directory then uploads them via JReleaser to the Sonatype Central
 * Portal. Retried per {@code ike.deploy.central.{maxAttempts,backoffSeconds}}.
 * Central failure is best-effort and does not throw: Nexus already
 * has the artifact by the time this runs, so the team is unblocked
 * and tag/main push and the GitHub Release proceed regardless.
 *
 * <p>{@link #execute()} returns a {@link CompletableFuture} per the
 * Phase 4 decision §1.1 — the standalone mojo joins the future
 * immediately to preserve today's blocking semantics; the Phase 5
 * orchestrator forks it as a subtask alongside {@code FinalizePhase}
 * under a single {@code StructuredTaskScope}.
 *
 * <p>The detached async-bash spawn path
 * ({@code ReleaseDraftMojo.spawnCentralDeployAsync}) remains on the
 * mojo for now and is the wedge that Phase 5 replaces with
 * structured concurrency; this class covers only the sync deploy path.
 *
 * <p>Carved out of {@code ReleaseDraftMojo.deployToMavenCentralWithRetry()}
 * and {@code deployToMavenCentralCore()} during the Phase 4 Commit 3
 * (IKE-Network/ike-issues#489).
 */
public final class CentralPhase {

    private final ReleaseContext ctx;

    /**
     * Creates a new Central phase bound to the given context.
     *
     * @param ctx the per-invocation release context
     */
    public CentralPhase(ReleaseContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Executes the Maven Central deploy with retry.
     *
     * <p>Retry parameters are read from {@code ctx.request()}:
     * {@link network.ike.plugin.release.ReleaseRequest#centralDeployMaxAttempts()}
     * and
     * {@link network.ike.plugin.release.ReleaseRequest#centralDeployBackoffSeconds()}.
     * The caller is responsible for skip-decision logic
     * ({@code skipCentralDeploy}, missing credentials,
     * {@code centralDeployAsync} routing); this method runs the sync
     * deploy unconditionally when invoked.
     *
     * <p>Returns a {@link CompletableFuture#completedFuture}-wrapped
     * outcome. The future never completes exceptionally — exhausted
     * retries are surfaced through {@link CentralOutcome#failureSummary()}
     * with the failure logged as a warning. The release continues to
     * tag/main push regardless of Central's outcome (Nexus has the
     * artifact; team is unblocked).
     *
     * @return a completed future carrying the {@link CentralOutcome}
     */
    public CompletableFuture<CentralOutcome> execute() {
        CentralOutcome outcome = CentralOutcome.initial();
        int maxAttempts = ctx.request().centralDeployMaxAttempts();
        int[] backoff = RetrySchedule.parseSeconds(
                "ike.deploy.central.backoffSeconds",
                ctx.request().centralDeployBackoffSeconds());
        Throwable last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            outcome = outcome.withAttempts(attempt);
            if (attempt > 1) {
                int wait = backoff[Math.min(attempt - 2,
                        backoff.length - 1)];
                RetrySchedule.sleepBefore(ctx.log(), wait, "Maven Central deploy",
                        attempt, maxAttempts);
            }
            ctx.log().info("Publishing to Maven Central (cycle "
                    + attempt + "/" + maxAttempts + ")...");
            try {
                deployToMavenCentralCore();
                return CompletableFuture.completedFuture(outcome.withSucceeded(true));
            } catch (MojoException e) {
                last = e;
                ctx.log().warn("Maven Central cycle " + attempt
                        + "/" + maxAttempts
                        + " failed: " + e.getMessage());
            }
        }
        String releaseVersion = ctx.request().releaseVersion();
        outcome = outcome.withFailureSummary(last == null
                ? "unknown failure"
                : last.getMessage());
        ctx.log().warn("Maven Central deploy did not succeed after "
                + maxAttempts + " cycles. "
                + "Nexus already has v" + releaseVersion
                + "; tag and main will still publish. "
                + "To retry Central later: check out v"
                + releaseVersion + " and run "
                + "`mvn jreleaser:deploy`.");
        return CompletableFuture.completedFuture(outcome);
    }

    /**
     * Single-shot Central staging + JReleaser upload. The retry
     * loop in {@link #execute()} calls this per attempt.
     *
     * <p>Three steps: a signed {@code clean deploy} to a local
     * staging directory ({@code target/staging-deploy}); a prune of
     * the Maven 4 {@code -build.pom} artifacts (build-time only, not
     * published to Central); then {@code jreleaser:deploy}, which
     * uploads the staged bundle to the Sonatype Central Portal.
     */
    private void deployToMavenCentralCore() {
        File gitRoot = ctx.gitRoot();
        File mvnw = ctx.mvnw();
        Path stagingDir = gitRoot.toPath()
                .resolve("target").resolve("staging-deploy");
        ctx.log().info("Staging signed artifacts for Maven Central...");
        ReleaseSupport.exec(gitRoot, ctx.log(),
                mvnw.getAbsolutePath(), "clean", "deploy", "-B", "-T", "1",
                "-P", "release,signArtifacts",
                "-DaltDeploymentRepository=local::file://"
                        + stagingDir.toAbsolutePath());
        pruneBuildPoms(stagingDir);
        ctx.log().info("Uploading bundle via JReleaser...");
        // -N (non-recursive): jreleaser:deploy runs once, at the reactor
        // root, uploading the whole staging directory as a single bundle.
        // Without it the goal runs per module — the first invocation
        // publishes everything, the rest fail "artifacts already deployed".
        ReleaseSupport.exec(gitRoot, ctx.log(),
                mvnw.getAbsolutePath(), "jreleaser:deploy", "-N", "-B");
    }

    /**
     * Deletes the Maven 4 {@code -build.pom} artifacts — and their
     * signatures and checksums — from the staging directory. The
     * build POM carries the 4.1.0 model and is build-time only;
     * Maven Central publishes the consumer POM (the main
     * {@code .pom}). One build POM is produced per reactor module,
     * so the directory is walked recursively.
     */
    private void pruneBuildPoms(Path stagingDir) {
        try (var paths = Files.walk(stagingDir)) {
            List<Path> buildPoms = paths
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString()
                            .contains("-build.pom"))
                    .toList();
            for (Path p : buildPoms) {
                Files.delete(p);
                ctx.log().info("  pruned " + stagingDir.relativize(p));
            }
        } catch (IOException e) {
            throw new MojoException(
                    "Failed to prune -build.pom artifacts from "
                            + stagingDir, e);
        }
    }
}
