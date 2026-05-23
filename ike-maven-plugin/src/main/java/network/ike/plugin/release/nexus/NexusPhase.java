package network.ike.plugin.release.nexus;

import network.ike.plugin.ReleaseSupport;
import network.ike.plugin.release.ReleaseContext;
import network.ike.plugin.release.RetrySchedule;
import org.apache.maven.api.plugin.MojoException;

import java.io.File;

/**
 * The Nexus deploy phase of the release pipeline.
 *
 * <p>Runs {@code mvn clean deploy -P release,signArtifacts} from the
 * release-tagged worktree, with a bounded retry budget configured by
 * {@code ike.deploy.nexus.{maxAttempts,backoffSeconds}}. Nexus is the
 * mandatory deploy: a release with no Nexus artifact has no internal
 * consumers unblocked, so exhausting the retry budget aborts the
 * release before any tag or main push.
 *
 * <p>Carved out of {@code ReleaseDraftMojo.deployToNexusWithRetry()}
 * during the Phase 4 Commit 2 (IKE-Network/ike-issues#489).
 */
public final class NexusPhase {

    private final ReleaseContext ctx;

    /**
     * Creates a new Nexus phase bound to the given context.
     *
     * @param ctx the per-invocation release context
     */
    public NexusPhase(ReleaseContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Executes the Nexus deploy with retry.
     *
     * <p>Retry parameters are read from {@code ctx.request()}:
     * {@link network.ike.plugin.release.ReleaseRequest#nexusDeployMaxAttempts()}
     * and
     * {@link network.ike.plugin.release.ReleaseRequest#nexusDeployBackoffSeconds()}.
     * The caller is responsible for skipping this phase entirely
     * when {@code skipNexusDeploy} is set; this method does not
     * inspect that flag.
     *
     * @return a {@link NexusOutcome} marked {@code succeeded=true} on the first
     *         successful cycle, with {@code attempts} set to the cycle on which
     *         success occurred
     * @throws MojoException after the final attempt fails — the release is
     *                       aborted before any tag or main push
     */
    public NexusOutcome execute() throws MojoException {
        File gitRoot = ctx.gitRoot();
        File mvnw = ctx.mvnw();
        int maxAttempts = ctx.request().nexusDeployMaxAttempts();
        int[] backoff = RetrySchedule.parseSeconds(
                "ike.deploy.nexus.backoffSeconds",
                ctx.request().nexusDeployBackoffSeconds());
        NexusOutcome outcome = NexusOutcome.initial();
        Throwable last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            outcome = outcome.withAttempts(attempt);
            if (attempt > 1) {
                int wait = backoff[Math.min(attempt - 2,
                        backoff.length - 1)];
                RetrySchedule.sleepBefore(ctx.log(), wait, "Nexus deploy",
                        attempt, maxAttempts);
            }
            ctx.log().info("Deploying to Nexus (cycle "
                    + attempt + "/" + maxAttempts + ")...");
            try {
                ReleaseSupport.exec(gitRoot, ctx.log(),
                        mvnw.getAbsolutePath(), "clean", "deploy",
                        "-B", "-T", "1", "-P", "release,signArtifacts");
                return outcome.withSucceeded(true);
            } catch (MojoException e) {
                last = e;
                ctx.log().warn("Nexus deploy cycle " + attempt
                        + "/" + maxAttempts
                        + " failed: " + e.getMessage());
            }
        }
        throw new MojoException(
                "Nexus deploy failed after " + maxAttempts
                        + " cycles. The release is aborted before "
                        + "any tag or main push. Last error: "
                        + (last == null ? "(none captured)"
                                : last.getMessage()),
                last);
    }
}
