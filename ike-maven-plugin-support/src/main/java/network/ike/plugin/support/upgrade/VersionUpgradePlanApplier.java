package network.ike.plugin.support.upgrade;

import network.ike.workspace.LiteralVersionUpgrade;
import network.ike.workspace.NodeVersionUpgrade;
import network.ike.workspace.ParentVersionUpgrade;
import network.ike.workspace.PropertyVersionUpgrade;
import network.ike.workspace.VersionUpgradeStatus;
import org.openrewrite.xml.XmlParser;
import org.openrewrite.xml.tree.Xml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Applies a {@link NodeVersionUpgrade} to a POM file, mutating only
 * the elements named in the plan and preserving everything else.
 *
 * <p>Uses OpenRewrite's XML LST so formatting, comments, and
 * whitespace round-trip cleanly. We never regex-replace POM XML —
 * see {@code feedback_no_sed_on_poms} and
 * {@code feedback_openrewrite_over_maven4_model}: regex chews
 * through structure, and the Maven 4 model API discards comments
 * and reformats whitespace.
 *
 * <p>What gets applied:
 * <ul>
 *   <li>{@link ParentVersionUpgrade} → updates the {@code <version>}
 *       child of {@code /project/parent} (matches groupId+artifactId
 *       defensively so we don't rewrite the wrong parent if multiple
 *       sit in profiles)</li>
 *   <li>{@link PropertyVersionUpgrade} → updates the value of
 *       {@code /project/properties/<name>}</li>
 *   <li>{@link LiteralVersionUpgrade} → updates the literal
 *       {@code <version>} of the matching {@code <plugin>} or
 *       {@code <dependency>} (anywhere it appears, including profile
 *       bodies and pluginManagement)</li>
 * </ul>
 *
 * <p>Entries with {@link VersionUpgradeStatus#BLOCKED} or
 * {@link VersionUpgradeStatus#PENDING_UPSTREAM} are skipped — those
 * are diagnostic entries, not actions.
 *
 * <p>If the plan calls for a coordinate that does not exist in the
 * POM (e.g., the user edited the POM between draft and publish), the
 * applier raises {@link VersionUpgradeApplyException} rather than
 * silently no-op-ing. Staleness is never silently absorbed.
 */
public final class VersionUpgradePlanApplier {

    private static final XmlParser PARSER = new XmlParser();

    private VersionUpgradePlanApplier() {}

    /**
     * Apply a node's plan to a POM file in place.
     *
     * @param pomPath path to {@code pom.xml}
     * @param node    the per-node upgrade entries
     * @return number of edits applied (parent + properties + literals,
     *         excluding skipped non-READY entries)
     * @throws VersionUpgradeApplyException on I/O error or when a plan
     *                                      entry cannot be located in
     *                                      the POM
     */
    public static int apply(Path pomPath, NodeVersionUpgrade node) {
        try {
            byte[] bytes = Files.readAllBytes(pomPath);
            String original = new String(bytes, StandardCharsets.UTF_8);
            ApplyResult result = apply(original, node);
            if (result.edits() > 0) {
                Files.writeString(pomPath, result.text());
            }
            return result.edits();
        } catch (IOException e) {
            throw new VersionUpgradeApplyException(
                    "Cannot apply plan to " + pomPath, e);
        }
    }

    /**
     * Apply a node's plan to POM XML content (no I/O).
     *
     * @param pomXml the POM XML content
     * @param node   the per-node upgrade entries
     * @return result with the new XML and the edit count
     * @throws VersionUpgradeApplyException if any READY entry cannot
     *                                      be located in the POM
     */
    public static ApplyResult apply(String pomXml, NodeVersionUpgrade node) {
        Xml.Document doc = PARSER.parse(pomXml)
                .findFirst()
                .map(t -> (Xml.Document) t)
                .orElseThrow(() -> new VersionUpgradeApplyException(
                        "Cannot parse POM"));
        Xml.Tag project = doc.getRoot();

        int edits = 0;

        // Parent — apply first so any inherited properties from a new
        // parent are in scope when the rest of the POM is evaluated
        // by Maven later. (Within this applier the order doesn't
        // matter for correctness.)
        if (node.parent() != null
                && node.parent().status() == VersionUpgradeStatus.READY) {
            project = applyParent(project, node.parent());
            edits++;
        }

        // Properties.
        for (PropertyVersionUpgrade prop : node.properties()) {
            if (prop.status() != VersionUpgradeStatus.READY) {
                continue;
            }
            project = applyProperty(project, prop);
            edits++;
        }

        // Literals.
        for (LiteralVersionUpgrade lit : node.literals()) {
            if (lit.status() != VersionUpgradeStatus.READY) {
                continue;
            }
            project = applyLiteral(project, lit);
            edits++;
        }

        Xml.Document updated = doc.withRoot(project);
        return new ApplyResult(updated.printAll(), edits);
    }

    // ── Apply helpers ──────────────────────────────────────────────

    private static Xml.Tag applyParent(Xml.Tag project,
                                        ParentVersionUpgrade upgrade) {
        Optional<Xml.Tag> parentOpt = project.getChild("parent");
        if (parentOpt.isEmpty()) {
            throw new VersionUpgradeApplyException(
                    "Plan calls for a parent upgrade but POM has no "
                            + "<parent> element");
        }
        Xml.Tag parent = parentOpt.get();
        String groupId = parent.getChildValue("groupId").orElse(null);
        String artifactId = parent.getChildValue("artifactId").orElse(null);
        if (!upgrade.groupId().equals(groupId)
                || !upgrade.artifactId().equals(artifactId)) {
            throw new VersionUpgradeApplyException(
                    "POM parent " + groupId + ":" + artifactId
                            + " does not match plan parent "
                            + upgrade.groupId() + ":" + upgrade.artifactId()
                            + " — POM may have been edited; please "
                            + "regenerate the plan");
        }
        Xml.Tag versionTag = parent.getChild("version")
                .orElseThrow(() -> new VersionUpgradeApplyException(
                        "Plan calls for parent version upgrade but "
                                + "<parent> has no <version>"));
        if (!upgrade.fromVersion().equals(versionTag.getValue().orElse(null))) {
            throw new VersionUpgradeApplyException(
                    "POM parent version "
                            + versionTag.getValue().orElse("(absent)")
                            + " does not match plan from-version "
                            + upgrade.fromVersion()
                            + " — POM may have been edited; please "
                            + "regenerate the plan");
        }
        Xml.Tag newVersion = versionTag.withValue(upgrade.toVersion());
        Xml.Tag newParent = replaceChild(parent, versionTag, newVersion);
        return replaceChild(project, parent, newParent);
    }

    private static Xml.Tag applyProperty(Xml.Tag project,
                                          PropertyVersionUpgrade upgrade) {
        Xml.Tag properties = project.getChild("properties")
                .orElseThrow(() -> new VersionUpgradeApplyException(
                        "Plan calls for property upgrade but POM has no "
                                + "<properties>"));
        Xml.Tag propertyTag = properties.getChild(upgrade.propertyName())
                .orElseThrow(() -> new VersionUpgradeApplyException(
                        "Plan calls for property '"
                                + upgrade.propertyName()
                                + "' but it is not declared in <properties>"));
        if (!upgrade.fromVersion().equals(
                propertyTag.getValue().orElse(null))) {
            throw new VersionUpgradeApplyException(
                    "Property '" + upgrade.propertyName() + "' value "
                            + propertyTag.getValue().orElse("(absent)")
                            + " does not match plan from-version "
                            + upgrade.fromVersion()
                            + " — POM may have been edited; please "
                            + "regenerate the plan");
        }
        Xml.Tag newProperty = propertyTag.withValue(upgrade.toVersion());
        Xml.Tag newProperties = replaceChild(properties, propertyTag,
                newProperty);
        return replaceChild(project, properties, newProperties);
    }

    private static Xml.Tag applyLiteral(Xml.Tag project,
                                         LiteralVersionUpgrade upgrade) {
        LiteralEdit edit = findLiteral(project, upgrade);
        if (edit == null) {
            throw new VersionUpgradeApplyException(
                    "Plan calls for literal upgrade of "
                            + upgrade.groupId() + ":"
                            + upgrade.artifactId()
                            + " from version " + upgrade.fromVersion()
                            + " but no matching <plugin>/<dependency> "
                            + "with that literal version was found");
        }
        Xml.Tag newVersion = edit.versionTag.withValue(upgrade.toVersion());
        Xml.Tag newCoord = replaceChild(edit.coordTag, edit.versionTag,
                newVersion);
        return rewritePath(project, edit.path, newCoord);
    }

    /**
     * Walk the project tree looking for the first {@code <plugin>} or
     * {@code <dependency>} whose groupId+artifactId+literal version
     * match {@code upgrade}. Records the path so we can splice the
     * mutation back in.
     */
    private static LiteralEdit findLiteral(Xml.Tag project,
                                            LiteralVersionUpgrade upgrade) {
        return walkForLiteral(project, new ArrayList<>(), upgrade);
    }

    private static LiteralEdit walkForLiteral(Xml.Tag tag, List<Xml.Tag> path,
                                               LiteralVersionUpgrade upgrade) {
        path.add(tag);
        try {
            if (("plugin".equals(tag.getName())
                    || "dependency".equals(tag.getName()))
                    && coordMatches(tag, upgrade)) {
                Optional<Xml.Tag> versionTag = tag.getChild("version");
                if (versionTag.isPresent()
                        && upgrade.fromVersion().equals(
                                versionTag.get().getValue().orElse(null))) {
                    return new LiteralEdit(new ArrayList<>(path), tag,
                            versionTag.get());
                }
            }
            if (tag.getContent() == null) {
                return null;
            }
            for (org.openrewrite.xml.tree.Content c : tag.getContent()) {
                if (c instanceof Xml.Tag child) {
                    LiteralEdit hit = walkForLiteral(child, path, upgrade);
                    if (hit != null) {
                        return hit;
                    }
                }
            }
            return null;
        } finally {
            path.remove(path.size() - 1);
        }
    }

    private static boolean coordMatches(Xml.Tag tag,
                                         LiteralVersionUpgrade upgrade) {
        return upgrade.groupId().equals(tag.getChildValue("groupId").orElse(null))
                && upgrade.artifactId().equals(
                        tag.getChildValue("artifactId").orElse(null));
    }

    /**
     * Splice {@code newCoord} into the tree along {@code path},
     * returning the new root project tag.
     */
    private static Xml.Tag rewritePath(Xml.Tag project, List<Xml.Tag> path,
                                        Xml.Tag newCoord) {
        // path is project -> ... -> coord (the original coord tag).
        // We rebuild from the leaf upward.
        Xml.Tag current = newCoord;
        for (int i = path.size() - 2; i >= 0; i--) {
            Xml.Tag parent = path.get(i);
            Xml.Tag oldChild = path.get(i + 1);
            current = replaceChild(parent, oldChild, current);
            path.set(i + 1, current);
        }
        return current;
    }

    private static Xml.Tag replaceChild(Xml.Tag parent, Xml.Tag oldChild,
                                         Xml.Tag newChild) {
        if (parent.getContent() == null) {
            return parent;
        }
        List<org.openrewrite.xml.tree.Content> newContent =
                new ArrayList<>(parent.getContent().size());
        for (org.openrewrite.xml.tree.Content c : parent.getContent()) {
            newContent.add(c == oldChild ? newChild : c);
        }
        return parent.withContent(newContent);
    }

    // ── Records ────────────────────────────────────────────────────

    /**
     * Result of applying a plan to in-memory POM content.
     *
     * @param text  the new POM XML
     * @param edits how many entries were applied
     */
    public record ApplyResult(String text, int edits) {}

    /** Internal: a found literal coord and its lineage. */
    private record LiteralEdit(
            List<Xml.Tag> path,
            Xml.Tag coordTag,
            Xml.Tag versionTag
    ) {}
}
