package network.ike.plugin.release.report;

import network.ike.plugin.release.ReleaseContext;
import network.ike.plugin.release.central.CentralOutcome;
import network.ike.plugin.release.nexus.NexusOutcome;
import network.ike.plugin.support.GoalReportBuilder;
import network.ike.workspace.cascade.CascadeReporter;
import network.ike.workspace.cascade.ProjectCascade;
import network.ike.workspace.cascade.ProjectCascadeIo;

import java.util.List;
import java.util.Optional;

/**
 * Renders the markdown report body and the cascade-preview/footer log
 * lines for {@code ike:release-draft} and {@code ike:release-publish}.
 *
 * <p>The report body is the markdown payload of the {@code GoalReportSpec}
 * returned by {@code ReleaseDraftMojo.runGoal()}; the IKE goal-report
 * aggregator collects it for the workspace-level run report. The
 * cascade log lines are advisory output emitted directly through
 * {@code ctx.log()}.
 *
 * <p>Carved out of {@code ReleaseDraftMojo} during the Phase 4
 * Commit 6 (IKE-Network/ike-issues#489). The companion
 * {@link DraftRenderer} wraps this class for the draft-mode short-circuit.
 */
public final class ReleaseReport {

    private final ReleaseContext ctx;

    /**
     * Creates a new report renderer bound to the given context.
     *
     * @param ctx the per-invocation release context
     */
    public ReleaseReport(ReleaseContext ctx) {
        this.ctx = ctx;
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
     * @param draft {@code true} for the draft preview, {@code false}
     *              for the post-publish footer
     */
    public void reportCascade(boolean draft) {
        try {
            Optional<ProjectCascade> loaded = ProjectCascadeIo.load(
                    ctx.gitRoot().toPath().resolve(
                            ProjectCascadeIo.MANIFEST_RELATIVE_PATH));
            if (loaded.isEmpty()) {
                // No release-cascade.yaml — an ordinary consumer, not
                // a foundation cascade member. Nothing to report.
                return;
            }
            String repo = ctx.gitRoot().getName();
            List<String> lines = draft
                    ? CascadeReporter.draftPreview(loaded.get(), repo)
                    : CascadeReporter.publishFooter(loaded.get(), repo);
            ctx.log().info("");
            lines.forEach(ctx.log()::info);
        } catch (RuntimeException e) {
            ctx.log().warn("Release cascade report skipped: "
                    + e.getMessage());
        }
    }

    /**
     * Builds the markdown body for an {@code ike:release-*} session report.
     *
     * @param draft            {@code true} for draft preview, {@code false}
     *                         for a completed publish run
     * @param oldVersion       the pre-release POM version
     * @param releaseBranch    the release branch that was (or would be) created
     * @param projectId        the artifactId of the project being released
     * @param releaseTimestamp the reproducible build timestamp stamped into
     *                         {@code project.build.outputTimestamp}
     * @param nexus            the Nexus deploy outcome (use {@code NexusOutcome.initial()} for draft)
     * @param central          the Central deploy outcome (use {@code CentralOutcome.initial()} for draft)
     * @return the markdown body
     */
    public String build(boolean draft, String oldVersion, String releaseBranch,
                        String projectId, String releaseTimestamp,
                        NexusOutcome nexus, CentralOutcome central) {
        String releaseVersion = ctx.request().releaseVersion();
        String nextVersion = ctx.request().nextVersion();
        boolean publishSite = ctx.request().publishSite();
        boolean publishToCentral = ctx.request().publishToCentral();
        boolean skipNexusDeploy = ctx.request().skipNexusDeploy();
        int nexusDeployMaxAttempts = ctx.request().nexusDeployMaxAttempts();
        int centralDeployMaxAttempts = ctx.request().centralDeployMaxAttempts();

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
                        nexus.attempts(), nexusDeployMaxAttempts))
                .append('\n');
        if (publishToCentral) {
            external.append(step++).append(". ").append(verb)
                    .append(" publish to Maven Central from tag `v")
                    .append(releaseVersion).append("`")
                    .append(centralOutcomeSuffix(central, centralDeployMaxAttempts))
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
                    .append(nexus.succeeded()
                            ? "✅ succeeded on cycle "
                                    + nexus.attempts() + "/"
                                    + nexusDeployMaxAttempts
                            : skipNexusDeploy
                                    ? "⚠ skipped (ike.skipNexusDeploy=true)"
                                    : "❌ did not run")
                    .append('\n');
            if (publishToCentral) {
                deploy.append("- **Maven Central:** ");
                if (central.asyncSpawned()) {
                    // Async path (#484) — outcome unknown at this point.
                    deploy.append("⏳ running async (#484) — track "
                                    + "with `mvn ")
                            .append("ike:central-status")
                            .append("`")
                            .append("\n  - Sentinel: `")
                            .append(central.sentinelPath())
                            .append("`\n  - Log: `")
                            .append(central.logPath())
                            .append('`');
                } else if (central.succeeded()) {
                    deploy.append("✅ succeeded on cycle ")
                            .append(central.attempts()).append("/")
                            .append(centralDeployMaxAttempts);
                } else if (central.skipReason() != null) {
                    deploy.append("⚠ skipped — ")
                            .append(central.skipReason());
                } else if (central.attempts() > 0) {
                    deploy.append("❌ failed after ")
                            .append(central.attempts()).append("/")
                            .append(centralDeployMaxAttempts)
                            .append(" cycles");
                    if (central.failureSummary() != null) {
                        deploy.append(" — ")
                                .append(central.failureSummary());
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
     * Renders a {@code " (cycle N/M)"} suffix for the post-release
     * report, or empty when no cycles were tracked (draft mode).
     */
    private static String deployAttemptSuffix(int attempts, int max) {
        if (attempts <= 0) {
            return "";
        }
        return " (cycle " + attempts + "/" + max + ")";
    }

    /**
     * Renders an outcome suffix for the Maven Central row in the
     * External-actions list. Distinguishes succeeded / skipped /
     * failed / pending-draft.
     */
    private static String centralOutcomeSuffix(CentralOutcome central, int maxAttempts) {
        if (central.asyncSpawned()) {
            return " (async — see Deploy details)";
        }
        if (central.succeeded()) {
            return " (cycle " + central.attempts() + "/"
                    + maxAttempts + ")";
        }
        if (central.skipReason() != null) {
            return " — skipped (" + central.skipReason() + ")";
        }
        if (central.attempts() > 0) {
            return " — FAILED after " + central.attempts() + "/"
                    + maxAttempts + " cycles";
        }
        return "";
    }
}
