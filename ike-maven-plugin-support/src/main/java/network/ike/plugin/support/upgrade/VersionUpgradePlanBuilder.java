package network.ike.plugin.support.upgrade;

import network.ike.workspace.LiteralVersionUpgrade;
import network.ike.workspace.NodeVersionUpgrade;
import network.ike.workspace.ParentVersionUpgrade;
import network.ike.workspace.PropertyVersionUpgrade;
import network.ike.workspace.VersionUpgradePlan;
import network.ike.workspace.VersionUpgradeRule;
import network.ike.workspace.VersionUpgradeRules;
import network.ike.workspace.VersionUpgradeScope;
import network.ike.workspace.VersionUpgradeStatus;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds a {@link VersionUpgradePlan} from one or more POM files plus
 * a {@link VersionUpgradeRules} ruleset.
 *
 * <p>For each POM:
 * <ol>
 *   <li>{@link PomScanner} reads the parent reference, version
 *       properties, and literal-versioned coordinates.</li>
 *   <li>For each candidate (parent, property, literal), the
 *       ruleset's {@link VersionUpgradeRules#resolve(String, String)}
 *       picks an action.</li>
 *   <li>If {@link VersionUpgradeRule.Action#ALLOW}, the
 *       {@link CandidateVersionResolver} is asked for the highest
 *       strictly-newer release. Result becomes
 *       {@link VersionUpgradeStatus#READY}; if no upgrade available,
 *       the entry is omitted.</li>
 *   <li>If {@link VersionUpgradeRule.Action#PIN}, the entry is
 *       {@link VersionUpgradeStatus#READY} with the pinned target
 *       (only if it differs from the current version).</li>
 *   <li>If {@link VersionUpgradeRule.Action#BLOCK}, the entry is
 *       included with {@link VersionUpgradeStatus#BLOCKED} and the
 *       rule's reason. Including blocked entries in the plan makes
 *       it auditable: the user can see why a coordinate was not
 *       upgraded.</li>
 * </ol>
 *
 * <p>For property entries: a property is upgradeable iff every
 * coordinate that references {@code ${name}} resolves to the same
 * action and (for {@code ALLOW}) the same highest candidate. If
 * coordinates disagree, the property is reported as
 * {@link VersionUpgradeStatus#BLOCKED} with a "conflicting consumers"
 * reason.
 *
 * <p>{@code PENDING_UPSTREAM} status is not produced here — that's
 * a workspace-scope concern (one node's release waits on another's),
 * applied by the workspace mojo on top of per-node plans.
 */
public final class VersionUpgradePlanBuilder {

    private final VersionUpgradeRules rules;
    private final CandidateVersionResolver resolver;

    /**
     * Build a planner with the given ruleset and version resolver.
     *
     * @param rules    the ruleset to consult
     * @param resolver the version resolver to ask for candidate versions
     */
    public VersionUpgradePlanBuilder(VersionUpgradeRules rules,
                                     CandidateVersionResolver resolver) {
        this.rules = rules;
        this.resolver = resolver;
    }

    /**
     * Build a single-module plan.
     *
     * @param nodeName           workspace node name (or module artifactId
     *                           for single-module use)
     * @param pomPath            path to the module's {@code pom.xml}
     * @param ikeToolingVersion  the {@code ike-tooling.version} property
     *                           value to stamp into the plan header;
     *                           may be null
     * @return a plan with {@link VersionUpgradeScope#MODULE}
     */
    public VersionUpgradePlan buildModulePlan(String nodeName, Path pomPath,
                                              String ikeToolingVersion) {
        NodeVersionUpgrade node = buildNode(nodeName, pomPath);
        Map<String, NodeVersionUpgrade> nodes = new LinkedHashMap<>();
        nodes.put(nodeName, node);
        String pomFingerprint = fingerprint(List.of(pomPath));
        return new VersionUpgradePlan("1.0", Instant.now().toString(),
                VersionUpgradeScope.MODULE, null, pomFingerprint,
                ikeToolingVersion, nodes);
    }

    /**
     * Build a workspace-scope plan over multiple POMs.
     *
     * <p>Nodes are emitted in the order they appear in
     * {@code nodePoms}. The caller is responsible for topo-ordering
     * the input map; this method does not re-order.
     *
     * @param nodePoms           ordered map of node name → POM path
     * @param ikeToolingVersion  the {@code ike-tooling.version} value
     *                           for the plan header
     * @return a plan with {@link VersionUpgradeScope#WORKSPACE}
     */
    public VersionUpgradePlan buildWorkspacePlan(
            Map<String, Path> nodePoms, String ikeToolingVersion) {
        Map<String, NodeVersionUpgrade> nodes = new LinkedHashMap<>();
        List<Path> poms = new ArrayList<>(nodePoms.size());
        for (Map.Entry<String, Path> entry : nodePoms.entrySet()) {
            nodes.put(entry.getKey(),
                    buildNode(entry.getKey(), entry.getValue()));
            poms.add(entry.getValue());
        }
        String pomFingerprint = fingerprint(poms);
        return new VersionUpgradePlan("1.0", Instant.now().toString(),
                VersionUpgradeScope.WORKSPACE, null, pomFingerprint,
                ikeToolingVersion, nodes);
    }

    // ── Per-node ───────────────────────────────────────────────────

    private NodeVersionUpgrade buildNode(String nodeName, Path pomPath) {
        PomScanner.PomScanResult scan = PomScanner.scan(pomPath);

        ParentVersionUpgrade parent = buildParent(scan.parent());
        List<PropertyVersionUpgrade> properties = buildProperties(scan);
        List<LiteralVersionUpgrade> literals = buildLiterals(scan);

        return new NodeVersionUpgrade(nodeName, parent, properties,
                literals);
    }

    private ParentVersionUpgrade buildParent(PomScanner.Coord parent) {
        if (parent == null) {
            return null;
        }
        VersionUpgradeRule rule = rules.resolve(
                parent.groupId(), parent.artifactId());
        return decideParent(parent, rule);
    }

    private ParentVersionUpgrade decideParent(PomScanner.Coord parent,
                                              VersionUpgradeRule rule) {
        return switch (rule.action()) {
            case BLOCK -> new ParentVersionUpgrade(
                    parent.groupId(), parent.artifactId(),
                    parent.version(), parent.version(),
                    VersionUpgradeStatus.BLOCKED,
                    rule.reason() == null
                            ? "blocked by ruleset"
                            : rule.reason());
            case PIN -> {
                String target = rule.pinnedVersion();
                if (target.equals(parent.version())) {
                    yield null; // already at pinned version, nothing to do
                }
                yield new ParentVersionUpgrade(
                        parent.groupId(), parent.artifactId(),
                        parent.version(), target,
                        VersionUpgradeStatus.READY, rule.reason());
            }
            case ALLOW -> {
                String target = resolver.resolveHighestCandidate(
                        parent.groupId(), parent.artifactId(),
                        parent.version());
                if (target == null) {
                    yield null; // already at latest
                }
                yield new ParentVersionUpgrade(
                        parent.groupId(), parent.artifactId(),
                        parent.version(), target,
                        VersionUpgradeStatus.READY, null);
            }
        };
    }

    private List<PropertyVersionUpgrade> buildProperties(
            PomScanner.PomScanResult scan) {
        List<PropertyVersionUpgrade> out = new ArrayList<>();
        for (Map.Entry<String, Set<PomScanner.Coord>> entry
                : scan.propertyToCoords().entrySet()) {
            String propertyName = entry.getKey();
            Set<PomScanner.Coord> consumers = entry.getValue();
            String currentValue = scan.versionProperties().get(propertyName);
            if (currentValue == null) {
                // Referenced in a <version>${prop}</version> but not
                // declared in <properties>. Could be inherited from
                // parent — skip for now; the parent POM's plan will
                // handle it.
                continue;
            }
            PropertyVersionUpgrade upgrade = decideProperty(
                    propertyName, currentValue, consumers);
            if (upgrade != null) {
                out.add(upgrade);
            }
        }
        return out;
    }

    private PropertyVersionUpgrade decideProperty(String propertyName,
                                                   String currentValue,
                                                   Set<PomScanner.Coord> consumers) {
        VersionUpgradeRule.Action sharedAction = null;
        String sharedTarget = null;
        String sharedReason = null;
        boolean conflict = false;
        for (PomScanner.Coord coord : consumers) {
            VersionUpgradeRule rule = rules.resolve(
                    coord.groupId(), coord.artifactId());
            String target = switch (rule.action()) {
                case BLOCK -> currentValue;
                case PIN -> rule.pinnedVersion();
                case ALLOW -> {
                    String candidate = resolver.resolveHighestCandidate(
                            coord.groupId(), coord.artifactId(),
                            currentValue);
                    yield candidate == null ? currentValue : candidate;
                }
            };
            if (sharedAction == null) {
                sharedAction = rule.action();
                sharedTarget = target;
                sharedReason = rule.reason();
            } else if (sharedAction != rule.action()
                    || (sharedTarget != null
                            && !sharedTarget.equals(target))) {
                conflict = true;
                break;
            }
        }
        if (conflict) {
            return new PropertyVersionUpgrade(propertyName, currentValue,
                    currentValue, VersionUpgradeStatus.BLOCKED,
                    "consumers of ${" + propertyName
                            + "} disagree on the upgrade target");
        }
        return switch (sharedAction) {
            case BLOCK -> new PropertyVersionUpgrade(propertyName,
                    currentValue, currentValue,
                    VersionUpgradeStatus.BLOCKED,
                    sharedReason == null
                            ? "blocked by ruleset" : sharedReason);
            case PIN -> sharedTarget.equals(currentValue)
                    ? null
                    : new PropertyVersionUpgrade(propertyName,
                            currentValue, sharedTarget,
                            VersionUpgradeStatus.READY, sharedReason);
            case ALLOW -> sharedTarget.equals(currentValue)
                    ? null
                    : new PropertyVersionUpgrade(propertyName,
                            currentValue, sharedTarget,
                            VersionUpgradeStatus.READY, null);
        };
    }

    private List<LiteralVersionUpgrade> buildLiterals(
            PomScanner.PomScanResult scan) {
        List<LiteralVersionUpgrade> out = new ArrayList<>();
        for (PomScanner.LiteralCoord lit : scan.literals()) {
            VersionUpgradeRule rule = rules.resolve(
                    lit.groupId(), lit.artifactId());
            LiteralVersionUpgrade entry = decideLiteral(lit, rule);
            if (entry != null) {
                out.add(entry);
            }
        }
        return out;
    }

    private LiteralVersionUpgrade decideLiteral(
            PomScanner.LiteralCoord lit, VersionUpgradeRule rule) {
        return switch (rule.action()) {
            case BLOCK -> new LiteralVersionUpgrade(
                    lit.groupId(), lit.artifactId(), lit.location(),
                    lit.version(), lit.version(),
                    VersionUpgradeStatus.BLOCKED,
                    rule.reason() == null
                            ? "blocked by ruleset" : rule.reason());
            case PIN -> {
                String target = rule.pinnedVersion();
                if (target.equals(lit.version())) {
                    yield null;
                }
                yield new LiteralVersionUpgrade(lit.groupId(),
                        lit.artifactId(), lit.location(), lit.version(),
                        target, VersionUpgradeStatus.READY, rule.reason());
            }
            case ALLOW -> {
                String target = resolver.resolveHighestCandidate(
                        lit.groupId(), lit.artifactId(), lit.version());
                if (target == null) {
                    yield null;
                }
                yield new LiteralVersionUpgrade(lit.groupId(),
                        lit.artifactId(), lit.location(), lit.version(),
                        target, VersionUpgradeStatus.READY, null);
            }
        };
    }

    // ── Fingerprint ────────────────────────────────────────────────

    private static String fingerprint(List<Path> poms) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (Path p : poms) {
                md.update(p.toString().getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
                md.update(Files.readAllBytes(p));
                md.update((byte) 0);
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder("sha256:");
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException | java.io.IOException e) {
            throw new PomScanException(
                    "Cannot compute POM fingerprint", e);
        }
    }
}
