package network.ike.plugin.release.finalize;

import network.ike.plugin.ReleaseNotesSupport;
import network.ike.plugin.ReleaseSupport;
import network.ike.plugin.release.ReleaseContext;
import org.apache.maven.api.plugin.MojoException;

import java.io.File;
import java.nio.file.Path;

/**
 * The finalize phase of the release pipeline — the last steps that
 * make the release externally visible beyond the Nexus deploy.
 *
 * <p>Runs after the {@code WorktreeGuard} has restored the worktree
 * to main and after the Nexus deploy (and best-effort Central deploy)
 * have completed. Performs:
 *
 * <ul>
 *   <li>B29 — pushes the release tag and main to origin
 *   <li>B30 — creates the GitHub Release with milestone-based notes,
 *       closes the milestone, removes pending-release labels from
 *       resolved issues
 * </ul>
 *
 * <p>The post-deploy log lines ("Release v X complete", Nexus/Central
 * outcome summary, GitHub Pages URLs) remain on the mojo for now —
 * they consume outcomes from multiple phases (Nexus, Central, Site)
 * and naturally land in {@code ReleaseExecution} when the orchestrator
 * is wired up. The reporting helpers ({@code reportCascade},
 * {@code buildReleaseReport}) also stay on the mojo because the draft
 * path invokes them too; they extract together in Commit 6.
 *
 * <p>Carved out of {@code ReleaseDraftMojo} during the Phase 4
 * Commit 1 (IKE-Network/ike-issues#489).
 */
public final class FinalizePhase {

    private final ReleaseContext ctx;

    /**
     * Creates a new finalize phase bound to the given context.
     *
     * @param ctx the per-invocation release context
     */
    public FinalizePhase(ReleaseContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Executes the finalize phase.
     *
     * <p>If {@link FinalizeInput#hasOrigin()} is {@code false}, pushes
     * and the GitHub Release step are skipped (with informational log
     * messages) — appropriate for a local-only release where no
     * remote is configured.
     *
     * @param input the inputs accumulated by {@code runGoal()} through the prior phases
     * @return a {@link FinalizeOutcome} recording which steps ran
     * @throws MojoException if either {@code git push} fails
     */
    public FinalizeOutcome execute(FinalizeInput input) throws MojoException {
        boolean tagPushed = false;
        boolean mainPushed = false;
        boolean githubReleaseAttempted = false;

        if (input.hasOrigin()) {
            ReleaseSupport.exec(ctx.gitRoot(), ctx.log(),
                    "git", "push", "origin", "v" + input.releaseVersion());
            tagPushed = true;
            ReleaseSupport.exec(ctx.gitRoot(), ctx.log(),
                    "git", "push", "origin", "main");
            mainPushed = true;

            createGitHubRelease(input.projectId(), input.releaseVersion(),
                    input.foundationUpgrades());
            githubReleaseAttempted = true;
        } else {
            ctx.log().info("No 'origin' remote — skipping push");
            ctx.log().info("No 'origin' remote — skipping GitHub Release");
        }

        return new FinalizeOutcome(tagPushed, mainPushed, githubReleaseAttempted);
    }

    /**
     * Creates the GitHub Release for {@code v<version>} with
     * milestone-based release notes, then closes the milestone and
     * removes the {@code pending-release} label from any issues
     * resolved in this release range.
     *
     * <p>Looks for a milestone named {@code <projectId> v<version>}
     * in the configured issue repository ({@code ctx.request().issueRepo()}).
     * If found, formats its closed issues as the GitHub Release body.
     * Falls back to GitHub's auto-generated commit-based notes when no
     * milestone exists.
     *
     * <p>All three steps (release create, milestone close, label
     * cleanup) are best-effort once we reach this point: the Nexus
     * deploy already shipped the artifact, so any failure here is
     * logged as a warning with a manual-retry command rather than
     * thrown as an exception.
     *
     * @param projectId the project's Maven artifact id
     * @param version   the released version (no {@code -SNAPSHOT})
     * @param foundationUpgrades the upstream-version bumps this release applied;
     *                  rendered as a "Foundation upgrades" section so a
     *                  cascade-only rebuild's notes are never empty (#706)
     */
    private void createGitHubRelease(String projectId, String version,
            java.util.List<network.ike.plugin.CascadeBump> foundationUpgrades) {
        File gitRoot = ctx.gitRoot();
        String issueRepo = ctx.request().issueRepo();
        String milestoneName = projectId + " v" + version;

        Path notesFile;
        try {
            notesFile = ReleaseNotesSupport.generateToFile(
                    issueRepo, milestoneName, foundationUpgrades, ctx.log());
        } catch (Exception e) {
            // A GitHub API failure here (rate limit, auth, network) must not
            // fail a release whose artifacts have already shipped — fall back
            // to GitHub's auto-generated notes (IKE-Network/ike-issues#572).
            ctx.log().warn("Could not generate milestone notes for \""
                    + milestoneName + "\" — falling back to auto-generated "
                    + "notes: " + e.getMessage());
            notesFile = null;
        }

        try {
            if (notesFile != null) {
                ctx.log().info("Release notes generated from milestone: "
                        + milestoneName);
                ReleaseSupport.exec(gitRoot, ctx.log(),
                        "gh", "release", "create", "v" + version,
                        "--title", version,
                        "--notes-file", notesFile.toString(),
                        "--verify-tag");
            } else {
                ctx.log().info("No milestone \"" + milestoneName
                        + "\" found — using auto-generated notes");
                ReleaseSupport.exec(gitRoot, ctx.log(),
                        "gh", "release", "create", "v" + version,
                        "--title", version,
                        "--generate-notes", "--verify-tag");
            }
        } catch (Exception e) {
            ctx.log().warn("GitHub Release creation failed "
                    + "(gh CLI may not be installed): " + e.getMessage());
            ctx.log().warn("Run manually: gh release create v" + version
                    + " --title " + version + " --generate-notes");
        }

        if (notesFile != null) {
            try {
                ReleaseNotesSupport.closeMilestone(issueRepo, milestoneName, ctx.log());
            } catch (Exception e) {
                ctx.log().warn("Could not close milestone (release succeeded): "
                        + e.getMessage());
                ctx.log().warn("Close manually: gh api repos/" + issueRepo
                        + "/milestones/1 -X PATCH -f state=closed");
            }
        }

        try {
            ReleaseNotesSupport.removePendingReleaseLabels(
                    gitRoot, null, "v" + version, issueRepo, ctx.log());
        } catch (Exception e) {
            ctx.log().warn("Could not remove pending-release labels "
                    + "(release succeeded): " + e.getMessage());
        }
    }
}
