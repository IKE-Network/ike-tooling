package network.ike.plugin;

import network.ike.plugin.support.AbstractGoalMojo;
import network.ike.plugin.support.GoalReportSpec;
import network.ike.plugin.support.upgrade.SessionCandidateVersionResolver;
import network.ike.plugin.support.upgrade.VersionUpgradePlanBuilder;
import network.ike.workspace.LiteralVersionUpgrade;
import network.ike.workspace.NodeVersionUpgrade;
import network.ike.workspace.ParentVersionUpgrade;
import network.ike.workspace.PropertyVersionUpgrade;
import network.ike.workspace.VersionUpgradePlan;
import network.ike.workspace.VersionUpgradePlanWriter;
import network.ike.workspace.VersionUpgradeNoise;
import network.ike.workspace.VersionUpgradeRule;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
    protected GoalReportSpec runGoal() throws MojoException {
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

        return new GoalReportSpec(IkeGoal.VERSIONS_UPGRADE_DRAFT,
                projectRoot,
                buildReport(plan, rulesPath, planPath, rules));
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
                               Path planPath, VersionUpgradeRules rules) {
        // Single-module variant of the report from #384. Mirrors
        // WsVersionsUpgradeDraftMojo's structure: drop pure-noise
        // entries entirely, surface from==to conflicts as Warnings,
        // group blocked entries by groupId with a suggested allow-rule.
        List<ActionableEntry> ready = new ArrayList<>();
        List<ActionableEntry> blocked = new ArrayList<>();
        List<ActionableEntry> pending = new ArrayList<>();
        List<ActionableEntry> warnings = new ArrayList<>();
        for (Map.Entry<String, NodeVersionUpgrade> nodeEntry
                : plan.nodes().entrySet()) {
            collectActionable(nodeEntry.getKey(), nodeEntry.getValue(),
                    ready, blocked, pending, warnings);
        }

        Path reportDir = planPath.getParent();
        String planLink = reportDir == null ? planPath.toString()
                : reportDir.relativize(planPath).toString();
        String rulesLink = reportDir == null ? rulesPath.toString()
                : reportDir.relativize(rulesPath).toString();

        StringBuilder sb = new StringBuilder();
        sb.append("**Project:** ").append(project.getArtifactId())
                .append("\n");
        sb.append("**Scope:** module\n");
        sb.append("**Files to edit:** [`")
                .append(planLink).append("`](").append(planLink)
                .append(") · [`")
                .append(rulesLink).append("`](").append(rulesLink)
                .append(")\n");
        if (plan.ikeToolingVersion() != null) {
            sb.append("**ike-tooling.version:** `")
                    .append(plan.ikeToolingVersion()).append("`\n");
        }
        sb.append("**Generated:** ").append(plan.generated()).append("\n");
        sb.append("**Ready:** ").append(ready.size())
                .append("  ·  **Blocked:** ").append(blocked.size())
                .append("  ·  **Pending upstream:** ").append(pending.size())
                .append("  ·  **Warnings:** ").append(warnings.size())
                .append("\n\n");

        appendNextSteps(sb, planLink, rulesLink,
                ready.size(), blocked.size(), warnings.size());
        appendActiveRules(sb, rules);
        appendWarnings(sb, warnings);
        appendReady(sb, ready);
        appendBlockedGrouped(sb, blocked);
        appendPending(sb, pending);
        appendStandardsLink(sb);
        return sb.toString();
    }

    /** ike-issues#384: top-of-report numbered next-steps section. */
    private static void appendNextSteps(StringBuilder sb,
                                         String planLink, String rulesLink,
                                         int readyCount, int blockedCount,
                                         int warningCount) {
        sb.append("## Next steps\n\n");
        int step = 0;
        if (warningCount > 0) {
            sb.append(++step).append(". **Resolve the ")
                    .append(warningCount).append(" warning")
                    .append(warningCount == 1 ? "" : "s")
                    .append(" below** — conflicts / ambiguities that")
                    .append(" block an upgrade even though no version")
                    .append(" change is proposed.\n");
        }
        sb.append(++step).append(". **Review the ").append(readyCount)
                .append(" ready upgrade")
                .append(readyCount == 1 ? "" : "s")
                .append("** in the Ready section.\n");
        if (blockedCount > 0) {
            sb.append(++step).append(". **(Optional) Allow more coordinates**")
                    .append(" — the Blocked section groups ").append(blockedCount)
                    .append(" entr").append(blockedCount == 1 ? "y" : "ies")
                    .append(" by groupId with a copy-paste-ready allow-rule.")
                    .append(" Paste any you want into `").append(rulesLink)
                    .append("` and re-draft to pick them up.\n");
        }
        sb.append(++step).append(". **Edit the plan** to remove or re-pin")
                .append(" specific entries: [`").append(planLink)
                .append("`](").append(planLink).append(").\n");
        sb.append(++step).append(". **Apply** with")
                .append(" `mvn ike:versions-upgrade-publish`.\n\n");
    }

    private static void appendActiveRules(StringBuilder sb,
                                           VersionUpgradeRules rules) {
        if (rules == null) return;
        sb.append("## Active rules\n\n");
        sb.append("From the ruleset, in declaration order (first match wins):\n\n");
        for (VersionUpgradeRule rule : rules.rules()) {
            sb.append("- `").append(rule.groupIdPattern()).append(":")
                    .append(rule.artifactIdPattern()).append("`")
                    .append(" → **").append(rule.action().name()
                            .toLowerCase(Locale.ROOT))
                    .append("**");
            if (rule.pinnedVersion() != null
                    && !rule.pinnedVersion().isEmpty()) {
                sb.append(" (pin to `").append(rule.pinnedVersion())
                        .append("`)");
            }
            if (rule.reason() != null && !rule.reason().isEmpty()) {
                sb.append(" — ").append(rule.reason());
            }
            sb.append("\n");
        }
        sb.append("- _(default)_ → **")
                .append(rules.defaultAction().name()
                        .toLowerCase(Locale.ROOT))
                .append("**\n\n");
    }

    private static void appendWarnings(StringBuilder sb,
                                        List<ActionableEntry> warnings) {
        if (warnings.isEmpty()) return;
        sb.append("## Warnings (").append(warnings.size()).append(")\n\n");
        sb.append("These coordinates did **not** get an upgrade proposal,")
                .append(" but the resolver flagged a real problem worth")
                .append(" investigating.\n\n");
        for (ActionableEntry w : warnings) {
            sb.append("- ").append(w.coordLabel()).append(" stays at `")
                    .append(w.fromVersion()).append("` — ")
                    .append(w.reason()).append("\n");
        }
        sb.append("\n");
    }

    private static void appendReady(StringBuilder sb,
                                     List<ActionableEntry> ready) {
        if (ready.isEmpty()) return;
        sb.append("## Ready (").append(ready.size()).append(")\n\n");
        sb.append("These will be applied by `ike:versions-upgrade-publish`.")
                .append(" Edit the plan file to drop or re-pin any.\n\n");
        sb.append("| Coordinate | From → To |\n");
        sb.append("|---|---|\n");
        for (ActionableEntry r : ready) {
            sb.append("| ").append(r.coordLabel()).append(" | `")
                    .append(r.fromVersion()).append("` → `")
                    .append(r.toVersion()).append("` |\n");
        }
        sb.append("\n");
    }

    private static void appendBlockedGrouped(StringBuilder sb,
                                              List<ActionableEntry> blocked) {
        if (blocked.isEmpty()) return;
        sb.append("## Blocked — newer available (")
                .append(blocked.size()).append(")\n\n");
        sb.append("Newer versions exist but the ruleset doesn't allow")
                .append(" the groupId. To allow a group, paste its")
                .append(" suggested rule into `versions-upgrade-rules.yaml`")
                .append(" and re-draft.\n\n");

        Map<String, List<ActionableEntry>> byGroup =
                new LinkedHashMap<>();
        for (ActionableEntry b : blocked) {
            byGroup.computeIfAbsent(b.groupId(),
                    k -> new ArrayList<>()).add(b);
        }

        sb.append("| GroupId | Coords | Suggested rule |\n");
        sb.append("|---|---|---|\n");
        for (Map.Entry<String, List<ActionableEntry>> g
                : byGroup.entrySet()) {
            sb.append("| `").append(g.getKey()).append("` | ")
                    .append(coordSummary(g.getValue())).append(" | ")
                    .append("`- match: \"").append(g.getKey())
                    .append(":*\"`<br>`  action: allow` |\n");
        }
        sb.append("\n");

        sb.append("<details><summary>Detail — every blocked entry,")
                .append(" grouped</summary>\n\n");
        for (Map.Entry<String, List<ActionableEntry>> g
                : byGroup.entrySet()) {
            sb.append("**`").append(g.getKey()).append("`**\n");
            for (ActionableEntry b : g.getValue()) {
                sb.append("- ").append(b.coordLabel()).append(": `")
                        .append(b.fromVersion()).append("` → `")
                        .append(b.toVersion()).append("`");
                if (b.reason() != null) {
                    sb.append(" — ").append(b.reason());
                }
                sb.append("\n");
            }
            sb.append("\n");
        }
        sb.append("</details>\n\n");
    }

    private static void appendPending(StringBuilder sb,
                                       List<ActionableEntry> pending) {
        if (pending.isEmpty()) return;
        sb.append("## Pending upstream (").append(pending.size())
                .append(")\n\n");
        sb.append("Re-draft after the upstream releases — the resolver")
                .append(" will pick up the new version.\n\n");
        for (ActionableEntry p : pending) {
            sb.append("- ").append(p.coordLabel()).append(": `")
                    .append(p.fromVersion()).append("` → `")
                    .append(p.toVersion()).append("`");
            if (p.reason() != null) sb.append(" — ").append(p.reason());
            sb.append("\n");
        }
        sb.append("\n");
    }

    private static void appendStandardsLink(StringBuilder sb) {
        sb.append("---\n\n");
        sb.append("See [`IKE-WORKSPACE.md`](https://github.com/IKE-Network/")
                .append("ike-tooling/blob/main/ike-build-standards/")
                .append("src/main/standards/IKE-WORKSPACE.md) for the")
                .append(" versions-upgrade conventions.\n");
    }

    private static String coordSummary(List<ActionableEntry> entries) {
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        for (ActionableEntry e : entries) seen.add(e.coordLabel());
        int max = 3;
        StringBuilder out = new StringBuilder();
        int i = 0;
        for (String label : seen) {
            if (i > 0) out.append(", ");
            out.append(label);
            if (++i >= max && seen.size() > max) {
                out.append(", … (").append(seen.size() - max)
                        .append(" more)");
                break;
            }
        }
        return out.toString();
    }

    private static void collectActionable(String nodeName,
                                           NodeVersionUpgrade node,
                                           List<ActionableEntry> ready,
                                           List<ActionableEntry> blocked,
                                           List<ActionableEntry> pending,
                                           List<ActionableEntry> warnings) {
        if (node.parent() != null) {
            ParentVersionUpgrade p = node.parent();
            classify(nodeName, p.groupId(),
                    "parent `" + p.groupId() + ":" + p.artifactId() + "`",
                    p.fromVersion(), p.toVersion(),
                    p.status(), p.reason(),
                    ready, blocked, pending, warnings);
        }
        for (PropertyVersionUpgrade prop : node.properties()) {
            classify(nodeName, "<property>",
                    "property `${" + prop.propertyName() + "}`",
                    prop.fromVersion(), prop.toVersion(),
                    prop.status(), prop.reason(),
                    ready, blocked, pending, warnings);
        }
        for (LiteralVersionUpgrade lit : node.literals()) {
            classify(nodeName, lit.groupId(),
                    "literal `" + lit.groupId() + ":" + lit.artifactId() + "`",
                    lit.fromVersion(), lit.toVersion(),
                    lit.status(), lit.reason(),
                    ready, blocked, pending, warnings);
        }
    }

    private static void classify(String nodeName, String groupId,
                                  String coordLabel,
                                  String fromVersion, String toVersion,
                                  VersionUpgradeStatus status, String reason,
                                  List<ActionableEntry> ready,
                                  List<ActionableEntry> blocked,
                                  List<ActionableEntry> pending,
                                  List<ActionableEntry> warnings) {
        if (VersionUpgradeNoise.isPureNoise(status, fromVersion,
                toVersion, reason)) {
            return;
        }
        ActionableEntry entry = new ActionableEntry(nodeName, groupId,
                coordLabel, fromVersion, toVersion, status, reason);
        if (VersionUpgradeNoise.isInformationalSameVersion(status,
                fromVersion, toVersion, reason)) {
            warnings.add(entry);
            return;
        }
        switch (status) {
            case READY -> ready.add(entry);
            case BLOCKED -> blocked.add(entry);
            case PENDING_UPSTREAM -> pending.add(entry);
        }
    }

    /** Display record used by the redesigned report builder. #384. */
    private record ActionableEntry(
            String node,
            String groupId,
            String coordLabel,
            String fromVersion,
            String toVersion,
            VersionUpgradeStatus status,
            String reason
    ) {}
}
