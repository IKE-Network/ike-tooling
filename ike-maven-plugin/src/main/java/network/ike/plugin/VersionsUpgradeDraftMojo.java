package network.ike.plugin;

import network.ike.plugin.support.AbstractGoalMojo;
import network.ike.plugin.support.upgrade.SessionCandidateVersionResolver;
import network.ike.plugin.support.upgrade.VersionUpgradePlanBuilder;
import network.ike.workspace.LiteralVersionUpgrade;
import network.ike.workspace.NodeVersionUpgrade;
import network.ike.workspace.ParentVersionUpgrade;
import network.ike.workspace.PropertyVersionUpgrade;
import network.ike.workspace.VersionUpgradePlan;
import network.ike.workspace.VersionUpgradePlanWriter;
import network.ike.workspace.VersionUpgradeRules;
import network.ike.workspace.VersionUpgradeRulesException;
import network.ike.workspace.VersionUpgradeRulesReader;
import network.ike.workspace.VersionUpgradeStatus;
import org.apache.maven.api.Project;
import org.apache.maven.api.Session;
import org.apache.maven.api.di.Inject;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Preview the version upgrades that {@code ike:versions-upgrade-publish}
 * would apply to the current module.
 *
 * <p>Scans the module's {@code pom.xml} for {@code <parent>}, version
 * properties (entries in {@code <properties>} consumed via
 * {@code ${name}} from a {@code <version>} element), and literal
 * {@code <version>} values on plugins and dependencies. For each
 * coordinate, consults the {@code versions-upgrade-rules.yaml} ruleset
 * to decide whether to {@code allow} (resolve the highest non-SNAPSHOT
 * release from the configured remotes), {@code pin} (force a specific
 * version), or {@code block} (record the coordinate as off-limits with
 * a reason). The result is serialized as
 * {@code versions-upgrade-plan.yaml} for human review.
 *
 * <p>This goal is read-only: it never modifies the POM. The companion
 * {@code ike:versions-upgrade-publish} consumes the plan file and
 * applies the {@code READY} entries via OpenRewrite (preserving
 * comments and formatting). Edit the plan between draft and publish
 * to remove entries you don't want, or change a {@code to:} value to
 * pin a different target.
 *
 * <p>If {@code versions-upgrade-rules.yaml} is absent the goal aborts
 * — there is no safe default. The {@code default-action: block}
 * convention means an empty ruleset would propose nothing, which is
 * indistinguishable from "everything is up to date" and would mask a
 * misconfigured ruleset. Create the file with at minimum:
 * <pre>
 * schema-version: "1.0"
 * default-action: block
 * rules:
 *   - match: "network.ike.*"
 *     action: allow
 * </pre>
 *
 * @see VersionsUpgradePublishMojo
 */
@Mojo(name = "versions-upgrade-draft", projectRequired = true)
public class VersionsUpgradeDraftMojo extends AbstractGoalMojo {

    /** The current Maven session — provides the version resolver. */
    @Inject
    private Session session;

    /** The current Maven project — provides the POM path and artifactId. */
    @Inject
    private Project project;

    /**
     * Path to the ruleset that controls which coordinates may be
     * upgraded. Defaults to {@code versions-upgrade-rules.yaml} in the
     * project root.
     */
    @Parameter(property = "rulesFile",
               defaultValue = "${project.basedir}/versions-upgrade-rules.yaml")
    String rulesFile;

    /**
     * Path the generated plan is written to. Defaults to
     * {@code versions-upgrade-plan.yaml} in the project root.
     */
    @Parameter(property = "outputFile",
               defaultValue = "${project.basedir}/versions-upgrade-plan.yaml")
    String outputFile;

    /**
     * The {@code ike-tooling.version} value at draft time, surfaced in
     * the plan header for human review. Defaults to the property of the
     * same name from the project's effective POM; null if the project
     * does not declare it.
     */
    @Parameter(property = "ike-tooling.version",
               defaultValue = "${ike-tooling.version}")
    String ikeToolingVersion;

    /** Creates this goal instance. */
    public VersionsUpgradeDraftMojo() {}

    @Override
    public void execute() throws MojoException {
        Path projectRoot = project.getBasedir();
        Path pomPath = project.getPomPath();
        Path rulesPath = Path.of(rulesFile);
        Path planPath = Path.of(outputFile);

        VersionUpgradeRules rules = loadRules(rulesPath);
        VersionUpgradePlanBuilder builder = new VersionUpgradePlanBuilder(
                rules, new SessionCandidateVersionResolver(session));

        String nodeName = project.getArtifactId();
        VersionUpgradePlan plan = builder.buildModulePlan(
                nodeName, pomPath, normalizeIkeToolingVersion());

        VersionUpgradePlanWriter.write(plan, planPath);

        logSummary(plan, rulesPath, planPath);

        writeReport(IkeGoal.VERSIONS_UPGRADE_DRAFT, projectRoot,
                buildReport(plan, rulesPath, planPath));
    }

    private VersionUpgradeRules loadRules(Path rulesPath) {
        if (!Files.isRegularFile(rulesPath)) {
            throw new MojoException(
                    "Ruleset not found: " + rulesPath
                            + "\n  Create this file with at minimum:\n"
                            + "    schema-version: \"1.0\"\n"
                            + "    default-action: block\n"
                            + "    rules:\n"
                            + "      - match: \"network.ike.*\"\n"
                            + "        action: allow\n"
                            + "  Or set -DrulesFile=<path> to point at"
                            + " a workspace-shared ruleset.");
        }
        try {
            return VersionUpgradeRulesReader.read(rulesPath);
        } catch (VersionUpgradeRulesException e) {
            throw new MojoException(
                    "Cannot read ruleset " + rulesPath + ": "
                            + e.getMessage(), e);
        }
    }

    private String normalizeIkeToolingVersion() {
        if (ikeToolingVersion == null) return null;
        // When the property is undeclared, Maven leaves the literal
        // ${ike-tooling.version} unresolved.
        if (ikeToolingVersion.startsWith("${")) return null;
        if (ikeToolingVersion.isBlank()) return null;
        return ikeToolingVersion;
    }

    private void logSummary(VersionUpgradePlan plan, Path rulesPath,
                            Path planPath) {
        getLog().info("");
        getLog().info("ike:versions-upgrade-draft");
        getLog().info("  ruleset: " + rulesPath);
        getLog().info("  plan:    " + planPath);
        if (plan.ikeToolingVersion() != null) {
            getLog().info("  ike-tooling.version: "
                    + plan.ikeToolingVersion());
        }
        getLog().info("");

        for (Map.Entry<String, NodeVersionUpgrade> entry
                : plan.nodes().entrySet()) {
            logNode(entry.getKey(), entry.getValue());
        }

        Counts counts = countActions(plan);
        getLog().info("");
        getLog().info("Summary: " + counts.summary());
        getLog().info("");
        getLog().info("Edit " + planPath.getFileName()
                + " to refine, then run ike:versions-upgrade-publish.");
    }

    private void logNode(String nodeName, NodeVersionUpgrade node) {
        getLog().info("Node: " + nodeName);
        if (node.parent() != null) {
            ParentVersionUpgrade p = node.parent();
            getLog().info("  parent " + p.groupId() + ":" + p.artifactId()
                    + ": " + p.fromVersion() + " -> " + p.toVersion()
                    + "  [" + statusLabel(p.status()) + "]"
                    + reasonSuffix(p.reason()));
        }
        for (PropertyVersionUpgrade prop : node.properties()) {
            getLog().info("  property ${" + prop.propertyName() + "}: "
                    + prop.fromVersion() + " -> " + prop.toVersion()
                    + "  [" + statusLabel(prop.status()) + "]"
                    + reasonSuffix(prop.reason()));
        }
        for (LiteralVersionUpgrade lit : node.literals()) {
            getLog().info("  literal " + lit.groupId() + ":"
                    + lit.artifactId() + ": "
                    + lit.fromVersion() + " -> " + lit.toVersion()
                    + "  [" + statusLabel(lit.status()) + "]"
                    + reasonSuffix(lit.reason()));
        }
        if (node.parent() == null
                && node.properties().isEmpty()
                && node.literals().isEmpty()) {
            getLog().info("  (no upgrades proposed)");
        }
    }

    private static String statusLabel(VersionUpgradeStatus status) {
        return status.name().toLowerCase().replace('_', '-');
    }

    private static String reasonSuffix(String reason) {
        return reason == null ? "" : "  — " + reason;
    }

    private static Counts countActions(VersionUpgradePlan plan) {
        int ready = 0;
        int blocked = 0;
        int pending = 0;
        for (NodeVersionUpgrade node : plan.nodes().values()) {
            if (node.parent() != null) {
                switch (node.parent().status()) {
                    case READY -> ready++;
                    case BLOCKED -> blocked++;
                    case PENDING_UPSTREAM -> pending++;
                }
            }
            for (PropertyVersionUpgrade p : node.properties()) {
                switch (p.status()) {
                    case READY -> ready++;
                    case BLOCKED -> blocked++;
                    case PENDING_UPSTREAM -> pending++;
                }
            }
            for (LiteralVersionUpgrade l : node.literals()) {
                switch (l.status()) {
                    case READY -> ready++;
                    case BLOCKED -> blocked++;
                    case PENDING_UPSTREAM -> pending++;
                }
            }
        }
        return new Counts(ready, blocked, pending);
    }

    private record Counts(int ready, int blocked, int pending) {
        String summary() {
            return ready + " ready, " + blocked + " blocked, "
                    + pending + " pending-upstream";
        }
    }

    private String buildReport(VersionUpgradePlan plan, Path rulesPath,
                               Path planPath) {
        StringBuilder sb = new StringBuilder();
        sb.append("**Project:** ").append(project.getArtifactId())
                .append("\n");
        sb.append("**Scope:** module\n");
        sb.append("**Ruleset:** `").append(rulesPath).append("`\n");
        sb.append("**Plan:** `").append(planPath).append("`\n");
        if (plan.ikeToolingVersion() != null) {
            sb.append("**ike-tooling.version:** `")
                    .append(plan.ikeToolingVersion()).append("`\n");
        }
        sb.append("**Generated:** ").append(plan.generated()).append("\n\n");

        for (Map.Entry<String, NodeVersionUpgrade> entry
                : plan.nodes().entrySet()) {
            appendNodeSection(sb, entry.getKey(), entry.getValue());
        }

        Counts counts = countActions(plan);
        sb.append("## Summary\n");
        sb.append("- ready:            ").append(counts.ready()).append("\n");
        sb.append("- blocked:          ").append(counts.blocked()).append("\n");
        sb.append("- pending-upstream: ").append(counts.pending()).append("\n");
        sb.append("\n");
        sb.append("Edit `").append(planPath.getFileName())
                .append("` to refine, then run "
                        + "`ike:versions-upgrade-publish`.\n");
        return sb.toString();
    }

    private static void appendNodeSection(StringBuilder sb, String nodeName,
                                          NodeVersionUpgrade node) {
        sb.append("## ").append(nodeName).append("\n");
        if (node.parent() != null) {
            ParentVersionUpgrade p = node.parent();
            sb.append("- parent `").append(p.groupId()).append(":")
                    .append(p.artifactId()).append("`: ")
                    .append(p.fromVersion()).append(" → ")
                    .append(p.toVersion()).append(" [")
                    .append(statusLabel(p.status())).append("]")
                    .append(reasonSuffix(p.reason())).append("\n");
        }
        for (PropertyVersionUpgrade prop : node.properties()) {
            sb.append("- property `${").append(prop.propertyName())
                    .append("}`: ").append(prop.fromVersion())
                    .append(" → ").append(prop.toVersion()).append(" [")
                    .append(statusLabel(prop.status())).append("]")
                    .append(reasonSuffix(prop.reason())).append("\n");
        }
        for (LiteralVersionUpgrade lit : node.literals()) {
            sb.append("- literal `").append(lit.groupId()).append(":")
                    .append(lit.artifactId()).append("`: ")
                    .append(lit.fromVersion()).append(" → ")
                    .append(lit.toVersion()).append(" [")
                    .append(statusLabel(lit.status())).append("]")
                    .append(reasonSuffix(lit.reason())).append("\n");
        }
        if (node.parent() == null
                && node.properties().isEmpty()
                && node.literals().isEmpty()) {
            sb.append("- _no upgrades proposed_\n");
        }
        sb.append("\n");
    }
}
