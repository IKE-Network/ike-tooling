package network.ike.plugin.release.report;

import network.ike.plugin.IkeGoal;
import network.ike.plugin.release.ReleaseContext;
import network.ike.plugin.release.central.CentralOutcome;
import network.ike.plugin.release.nexus.NexusOutcome;
import network.ike.plugin.release.prep.PrepOutcome;
import network.ike.plugin.support.GoalReportSpec;

import java.nio.file.Path;

/**
 * The draft-mode handler for the release pipeline.
 *
 * <p>When {@link PrepOutcome#draftMode()} is {@code true},
 * {@code ReleaseDraftMojo.runGoal()} short-circuits to this class
 * instead of running the publish-only phases. The renderer logs the
 * {@code [DRAFT] Would …} preview block, emits the cascade preview,
 * and builds the GoalReportSpec from {@link ReleaseReport}.
 *
 * <p>No mutation happens through this path: the draft is a read-only
 * preview of what a publish would do.
 *
 * <p>Carved out of {@code ReleaseDraftMojo.runGoal()} during the
 * Phase 4 Commit 6 (IKE-Network/ike-issues#489).
 */
public final class DraftRenderer {

    private final ReleaseContext ctx;

    /**
     * Creates a new draft renderer bound to the given context.
     *
     * @param ctx the per-invocation release context
     */
    public DraftRenderer(ReleaseContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Renders the draft-mode preview and returns the
     * {@link GoalReportSpec} for the {@code ike:release-draft} goal.
     *
     * @param prep          the prep outcome with {@code projectId} and {@code releaseTimestamp}
     * @param startDirPath  the working directory the goal was invoked from (used as the report's start dir)
     * @param oldVersion    the pre-release POM version
     * @param releaseBranch the {@code release/<version>} branch that would be created
     * @return the {@link GoalReportSpec} describing the draft run
     */
    public GoalReportSpec render(PrepOutcome prep, Path startDirPath,
                                  String oldVersion, String releaseBranch) {
        String releaseVersion = ctx.request().releaseVersion();
        String nextVersion = ctx.request().nextVersion();
        boolean publishSite = ctx.request().publishSite();
        boolean publishToCentral = ctx.request().publishToCentral();
        String projectId = prep.projectId();

        ctx.log().info("[DRAFT] Would create branch: " + releaseBranch);
        ctx.log().info("[DRAFT] Would set version: " + oldVersion
                + " -> " + releaseVersion);
        ctx.log().info("[DRAFT] Would stamp project.build.outputTimestamp: "
                + prep.releaseTimestamp());
        ctx.log().info("[DRAFT] Would resolve ${project.version} -> "
                + releaseVersion + " in all POMs");
        ctx.log().info("[DRAFT] Would run: mvnw clean verify -B");
        ctx.log().info("[DRAFT] Would commit, tag v" + releaseVersion);
        ctx.log().info("[DRAFT] Would restore ${project.version} references");
        ctx.log().info("[DRAFT] Would merge " + releaseBranch + " to main");
        ctx.log().info("[DRAFT] Would bump to next version: " + nextVersion);
        ctx.log().info("[DRAFT] --- all local work above, external below ---");
        if (publishSite) {
            ctx.log().info("[DRAFT] Would generate site (must succeed)");
        }
        ctx.log().info("[DRAFT] Would " + (publishToCentral
                ? "publish to Maven Central via JReleaser"
                : "deploy to Nexus") + " from tag v"
                + releaseVersion + " (critical)");
        if (publishSite) {
            ctx.log().info("[DRAFT] Would force-push staged site "
                    + "to gh-pages on origin (best-effort)");
            ctx.log().info("[DRAFT] Would publish at "
                    + "https://ike.network/" + projectId + "/");
        }
        ctx.log().info("[DRAFT] Would push tag and main to origin");
        ctx.log().info("[DRAFT] Would create GitHub Release");

        ReleaseReport report = new ReleaseReport(ctx);
        report.reportCascade(true);
        String body = report.build(true, oldVersion, releaseBranch, projectId,
                prep.releaseTimestamp(),
                NexusOutcome.initial(), CentralOutcome.initial());
        return new GoalReportSpec(IkeGoal.RELEASE_DRAFT, startDirPath, body);
    }
}
