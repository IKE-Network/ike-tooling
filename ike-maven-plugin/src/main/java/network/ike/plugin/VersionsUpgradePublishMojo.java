package network.ike.plugin;

import network.ike.plugin.support.AbstractGoalMojo;
import network.ike.plugin.support.GoalReportBuilder;
import network.ike.plugin.support.GoalReportSpec;
import network.ike.plugin.support.upgrade.VersionUpgradeApplyException;
import network.ike.plugin.support.upgrade.VersionUpgradePlanApplier;
import network.ike.plugin.support.upgrade.VersionUpgradePlanBuilder;
import network.ike.workspace.LiteralVersionUpgrade;
import network.ike.workspace.NodeVersionUpgrade;
import network.ike.workspace.ParentVersionUpgrade;
import network.ike.workspace.PropertyVersionUpgrade;
import network.ike.workspace.VersionUpgradePlan;
import network.ike.workspace.VersionUpgradePlanException;
import network.ike.workspace.VersionUpgradePlanReader;
import network.ike.workspace.VersionUpgradeScope;
import network.ike.workspace.VersionUpgradeStatus;
import org.apache.maven.api.Project;
import org.apache.maven.api.di.Inject;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Apply a previously drafted {@code versions-upgrade-plan.yaml} to the
 * current module's POM.
 *
 * <p>Reads the plan, locates the entry whose node name matches the
 * current project's {@code artifactId}, and rewrites
 * {@code <parent><version>}, {@code <properties>} entries, and literal
 * plugin/dependency versions to the targets recorded in the plan.
 * Edits are performed via OpenRewrite's XML LST so comments and
 * formatting round-trip cleanly. Entries whose status is not
 * {@code READY} are skipped — {@code BLOCKED} and
 * {@code PENDING_UPSTREAM} are diagnostic markers, not actions.
 *
 * <p><strong>Staleness check.</strong> Before any edit, the publish
 * recomputes the {@code pomFingerprint} of the current POM and compares
 * to the one stamped into the plan at draft time. If they differ, the
 * publish aborts with a "regenerate the plan" hint — staleness is
 * never silently absorbed. Pass {@code -DforceStale=true} to skip the
 * check (intended for scripted recovery; the plan applier still
 * re-validates each {@code from-version} per entry).
 *
 * <p>On success the plan file is deleted — the goal completes its
 * effect rather than leaving a transient artifact in the working
 * tree. Re-run {@code ike:versions-upgrade-draft} to regenerate it.
 * The markdown report written via {@code writeReport(...)} is the
 * durable record of what was applied.
 *
 * <p><strong>Missing plan is a clean no-op.</strong> When the
 * configured {@code planFile} does not exist on disk, the goal logs
 * {@code "no plan for <module>"} at info level and returns success.
 * This matters during a reactor cascade where a sibling module had
 * nothing to upgrade in the prior {@code -draft} run (or where the
 * prior publish already consumed and deleted its plan): a missing
 * plan is indistinguishable from "nothing to do" and should not
 * fail the cascade.
 *
 * @see VersionsUpgradeDraftMojo
 */
@Mojo(name = "versions-upgrade-publish", projectRequired = true)
public class VersionsUpgradePublishMojo extends AbstractGoalMojo {

    /** The current Maven project — provides the POM path and artifactId. */
    @Inject
    private Project project;

    /**
     * Path to the plan file written by
     * {@code ike:versions-upgrade-draft}. Defaults to
     * {@code versions-upgrade-plan.yaml} in the project root.
     */
    @Parameter(property = "planFile",
               defaultValue = "${project.basedir}/versions-upgrade-plan.yaml")
    String planFile;

    /**
     * If true, skip the POM fingerprint staleness check. The per-entry
     * {@code from-version} validation in {@link VersionUpgradePlanApplier}
     * still runs, so mismatches still abort — this only bypasses the
     * top-level fingerprint guard.
     */
    @Parameter(property = "forceStale", defaultValue = "false")
    boolean forceStale;

    /** Creates this goal instance. */
    public VersionsUpgradePublishMojo() {}

    @Override
    protected GoalReportSpec runGoal() throws MojoException {
        Path projectRoot = project.getBasedir();
        Path pomPath = project.getPomPath();
        Path planPath = Path.of(planFile);
        String nodeName = project.getArtifactId();

        if (!Files.isRegularFile(planPath)) {
            getLog().info("no plan for " + nodeName
                    + " (" + planPath + ") — nothing to publish.");
            return new GoalReportSpec(IkeGoal.VERSIONS_UPGRADE_PUBLISH,
                    projectRoot,
                    "No upgrade plan found for `" + nodeName + "` ("
                            + planPath + ") — nothing to publish.\n");
        }

        VersionUpgradePlan plan = readPlan(planPath);
        if (plan.scope() != VersionUpgradeScope.MODULE) {
            throw new MojoException(
                    "Plan scope is " + plan.scope()
                            + " — ike:versions-upgrade-publish only handles"
                            + " module-scope plans. Use the workspace"
                            + " plugin's ws:versions-upgrade-publish for"
                            + " workspace-scope plans.");
        }

        verifyFingerprint(plan, pomPath, planPath);

        NodeVersionUpgrade node = plan.nodes().get(nodeName);
        if (node == null) {
            throw new MojoException(
                    "Plan has no entry for node '" + nodeName + "'."
                            + " Plan covers: " + plan.nodes().keySet());
        }

        int edits = applyPlan(pomPath, node);

        try {
            Files.deleteIfExists(planPath);
            getLog().info("Removed plan file: " + planPath);
        } catch (IOException e) {
            getLog().warn("Could not delete plan " + planPath
                    + ": " + e.getMessage());
        }

        logSummary(node, edits, planPath);

        return new GoalReportSpec(IkeGoal.VERSIONS_UPGRADE_PUBLISH,
                projectRoot,
                buildReport(plan, node, edits, planPath));
    }

    private VersionUpgradePlan readPlan(Path planPath) {
        try {
            return VersionUpgradePlanReader.read(planPath);
        } catch (VersionUpgradePlanException e) {
            throw new MojoException(
                    "Cannot read plan " + planPath + ": " + e.getMessage(),
                    e);
        }
    }

    private void verifyFingerprint(VersionUpgradePlan plan, Path pomPath,
                                   Path planPath) {
        String stamped = plan.pomFingerprint();
        if (stamped == null || stamped.isBlank()) {
            getLog().warn("Plan has no pom-fingerprint — staleness check"
                    + " skipped. The applier's per-entry from-version"
                    + " checks remain in force.");
            return;
        }
        String current = VersionUpgradePlanBuilder.fingerprint(
                List.of(pomPath));
        if (stamped.equals(current)) {
            return;
        }
        if (forceStale) {
            getLog().warn("POM fingerprint differs from plan ("
                    + "current=" + current + ", plan=" + stamped
                    + ") — proceeding because -DforceStale=true.");
            return;
        }
        throw new MojoException(
                "POM fingerprint differs from the plan."
                        + "\n  Plan:    " + stamped
                        + "\n  Current: " + current
                        + "\n  The POM has been edited since"
                        + " ike:versions-upgrade-draft was run."
                        + " Regenerate the plan and re-publish."
                        + "\n  Plan file: " + planPath
                        + "\n  Bypass: -DforceStale=true (per-entry"
                        + " checks still apply).");
    }

    private int applyPlan(Path pomPath, NodeVersionUpgrade node) {
        try {
            return VersionUpgradePlanApplier.apply(pomPath, node);
        } catch (VersionUpgradeApplyException e) {
            throw new MojoException(
                    "Cannot apply plan: " + e.getMessage(), e);
        }
    }

    private void logSummary(NodeVersionUpgrade node, int edits,
                             Path planPath) {
        getLog().info("");
        getLog().info("ike:versions-upgrade-publish");
        getLog().info("  plan:    " + planPath);
        getLog().info("  node:    " + node.nodeName());
        getLog().info("  edits:   " + edits);
        getLog().info("");

        int blocked = countSkipped(node);
        if (blocked > 0) {
            getLog().info("  " + blocked + " non-ready entry/entries"
                    + " skipped (blocked or pending-upstream).");
        }
        if (edits == 0) {
            getLog().info("  No changes applied.");
        } else {
            getLog().info("  Applied " + edits + " upgrade(s).");
        }
    }

    private static int countSkipped(NodeVersionUpgrade node) {
        int skipped = 0;
        if (node.parent() != null
                && node.parent().status() != VersionUpgradeStatus.READY) {
            skipped++;
        }
        for (PropertyVersionUpgrade p : node.properties()) {
            if (p.status() != VersionUpgradeStatus.READY) skipped++;
        }
        for (LiteralVersionUpgrade l : node.literals()) {
            if (l.status() != VersionUpgradeStatus.READY) skipped++;
        }
        return skipped;
    }

    private String buildReport(VersionUpgradePlan plan,
                                NodeVersionUpgrade node, int edits,
                                Path planPath) {
        GoalReportBuilder report = new GoalReportBuilder();
        StringBuilder header = new StringBuilder();
        header.append("**Project:** ").append(project.getArtifactId())
                .append("\n");
        header.append("**Plan:** `").append(planPath).append("`\n");
        if (plan.planHash() != null) {
            header.append("**Plan hash:** `").append(plan.planHash())
                    .append("`\n");
        }
        if (plan.ikeToolingVersion() != null) {
            header.append("**ike-tooling.version:** `")
                    .append(plan.ikeToolingVersion()).append("`\n");
        }
        header.append("**Edits applied:** ").append(edits);
        report.paragraph(header.toString());

        report.section("Applied");
        appendApplied(report, node);
        int skipped = countSkipped(node);
        if (skipped > 0) {
            report.section("Skipped (non-ready)");
            appendSkipped(report, node);
        }
        return report.build();
    }

    private static void appendApplied(GoalReportBuilder report,
                                       NodeVersionUpgrade node) {
        boolean any = false;
        if (node.parent() != null
                && node.parent().status() == VersionUpgradeStatus.READY) {
            ParentVersionUpgrade p = node.parent();
            report.bullet("parent `" + p.groupId() + ":" + p.artifactId()
                    + "`: " + p.fromVersion() + " → " + p.toVersion());
            any = true;
        }
        for (PropertyVersionUpgrade prop : node.properties()) {
            if (prop.status() != VersionUpgradeStatus.READY) continue;
            report.bullet("property `${" + prop.propertyName() + "}`: "
                    + prop.fromVersion() + " → " + prop.toVersion());
            any = true;
        }
        for (LiteralVersionUpgrade lit : node.literals()) {
            if (lit.status() != VersionUpgradeStatus.READY) continue;
            report.bullet("literal `" + lit.groupId() + ":"
                    + lit.artifactId() + "`: " + lit.fromVersion()
                    + " → " + lit.toVersion());
            any = true;
        }
        if (!any) {
            report.bullet("_no ready upgrades_");
        }
    }

    private static void appendSkipped(GoalReportBuilder report,
                                       NodeVersionUpgrade node) {
        if (node.parent() != null
                && node.parent().status() != VersionUpgradeStatus.READY) {
            ParentVersionUpgrade p = node.parent();
            report.bullet("parent `" + p.groupId() + ":" + p.artifactId()
                    + "` [" + statusLabel(p.status()) + "]"
                    + reasonSuffix(p.reason()));
        }
        for (PropertyVersionUpgrade prop : node.properties()) {
            if (prop.status() == VersionUpgradeStatus.READY) continue;
            report.bullet("property `${" + prop.propertyName() + "}` ["
                    + statusLabel(prop.status()) + "]"
                    + reasonSuffix(prop.reason()));
        }
        for (LiteralVersionUpgrade lit : node.literals()) {
            if (lit.status() == VersionUpgradeStatus.READY) continue;
            report.bullet("literal `" + lit.groupId() + ":"
                    + lit.artifactId() + "` [" + statusLabel(lit.status())
                    + "]" + reasonSuffix(lit.reason()));
        }
    }

    private static String statusLabel(VersionUpgradeStatus status) {
        return status.name().toLowerCase().replace('_', '-');
    }

    private static String reasonSuffix(String reason) {
        return reason == null ? "" : " — " + reason;
    }
}
