package network.ike.plugin;

import org.openrewrite.xml.XPathMatcher;
import org.openrewrite.xml.XmlParser;
import org.openrewrite.xml.XmlVisitor;
import org.openrewrite.xml.tree.Content;
import org.openrewrite.xml.tree.Xml;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * AST-aware POM manipulation using OpenRewrite's XML LST.
 *
 * <p>Replaces regex-based POM editing with lossless semantic tree
 * (LST) transformations that preserve formatting, comments, and
 * whitespace. Each method parses the POM, applies a targeted change,
 * and serializes back to text.
 *
 * <p>Relocated from {@code ike-workspace-maven-plugin} (ike-platform)
 * to this module (ike-tooling) in {@code IKE-Network/ike-issues#348}
 * so the scaffold goals in {@code ike-maven-plugin} can apply
 * foundation drift without depending on a downstream artifact. The
 * earlier package-private visibility was widened to {@code public}
 * for the same reason.
 *
 * <p>Usage:
 * <pre>{@code
 * String updated = PomRewriter.updateDependencyVersion(
 *     pomContent, "network.ike", "ike-bom", "84");
 * }</pre>
 */
public final class PomRewriter {

    private PomRewriter() {}

    private static final XmlParser PARSER = new XmlParser();

    /**
     * Update the version of a specific dependency identified by
     * {@code groupId:artifactId} anywhere in the POM (both
     * {@code <dependencies>} and {@code <dependencyManagement>}).
     *
     * @param pomContent the raw POM text
     * @param groupId    dependency groupId to match
     * @param artifactId dependency artifactId to match
     * @param newVersion the version to set
     * @return updated POM text, or unchanged if no match
     */
    public static String updateDependencyVersion(String pomContent,
                                                  String groupId,
                                                  String artifactId,
                                                  String newVersion) {
        Xml.Document doc = parse(pomContent);
        if (doc == null) return pomContent;

        Xml.Document updated = (Xml.Document) new XmlVisitor<Integer>() {
            @Override
            public Xml.Tag visitTag(Xml.Tag tag, Integer ctx) {
                Xml.Tag t = (Xml.Tag) super.visitTag(tag, ctx);
                if (!"dependency".equals(t.getName())) return t;

                String gid = t.getChildValue("groupId").orElse(null);
                String aid = t.getChildValue("artifactId").orElse(null);
                if (groupId.equals(gid) && artifactId.equals(aid)) {
                    return t.withChildValue("version", newVersion);
                }
                return t;
            }
        }.visitNonNull(doc, 0);

        return print(updated);
    }

    /**
     * Read the version of the POM's {@code <parent>} block when its
     * coordinates match {@code parentGroupId:parentArtifactId}.
     *
     * <p>Companion read to {@link #updateParentVersion}: the cascade
     * alignment path uses this to inspect the current parent version
     * before deciding whether (and to what) to bump it. Returns the
     * version text exactly as it appears in the POM — a literal, an
     * unresolved {@code ${...}} reference, whatever is declared.
     *
     * @param pomContent       the raw POM text
     * @param parentGroupId    the parent groupId to match (required)
     * @param parentArtifactId the parent artifactId to match (required)
     * @return the parent's declared version, or empty when the POM
     *         has no {@code <parent>} block matching those
     *         coordinates
     */
    public static Optional<String> readParentVersion(String pomContent,
                                                      String parentGroupId,
                                                      String parentArtifactId) {
        Xml.Document doc = parse(pomContent);
        if (doc == null) {
            return Optional.empty();
        }
        String[] found = {null};
        new XmlVisitor<Integer>() {
            @Override
            public Xml.Tag visitTag(Xml.Tag tag, Integer ctx) {
                Xml.Tag t = (Xml.Tag) super.visitTag(tag, ctx);
                if (!"parent".equals(t.getName())) {
                    return t;
                }
                String gid = t.getChildValue("groupId").orElse(null);
                String aid = t.getChildValue("artifactId").orElse(null);
                if (parentGroupId.equals(gid)
                        && parentArtifactId.equals(aid)) {
                    found[0] = t.getChildValue("version").orElse(null);
                }
                return t;
            }
        }.visitNonNull(doc, 0);
        return Optional.ofNullable(found[0]);
    }

    /**
     * Update the parent version for a matching
     * {@code groupId:artifactId} in the POM's {@code <parent>} block.
     *
     * <p>Matching requires <strong>both</strong> groupId and
     * artifactId to match. This prevents cross-coordinate mutation
     * when the same artifactId lives under multiple groupIds
     * (e.g. {@code network.ike.platform:ike-parent} vs.
     * {@code network.ike.pipeline:ike-parent}) — see issue #241.
     *
     * @param pomContent       the raw POM text
     * @param parentGroupId    the parent groupId to match (required)
     * @param parentArtifactId the parent artifactId to match (required)
     * @param newVersion       the new version to set
     * @return updated POM text, or unchanged if no match
     */
    public static String updateParentVersion(String pomContent,
                                              String parentGroupId,
                                              String parentArtifactId,
                                              String newVersion) {
        Xml.Document doc = parse(pomContent);
        if (doc == null) return pomContent;

        Xml.Document updated = (Xml.Document) new XmlVisitor<Integer>() {
            @Override
            public Xml.Tag visitTag(Xml.Tag tag, Integer ctx) {
                Xml.Tag t = (Xml.Tag) super.visitTag(tag, ctx);
                if (!"parent".equals(t.getName())) return t;

                String gid = t.getChildValue("groupId").orElse(null);
                String aid = t.getChildValue("artifactId").orElse(null);
                if (parentGroupId.equals(gid)
                        && parentArtifactId.equals(aid)) {
                    return t.withChildValue("version", newVersion);
                }
                return t;
            }
        }.visitNonNull(doc, 0);

        return print(updated);
    }

    /**
     * Update a version property value in the POM's {@code <properties>}
     * block.
     *
     * @param pomContent   the raw POM text
     * @param propertyName the property name (e.g., "tinkar-core.version")
     * @param newValue     the new property value
     * @return updated POM text, or unchanged if no match
     */
    public static String updateProperty(String pomContent,
                                         String propertyName,
                                         String newValue) {
        Xml.Document doc = parse(pomContent);
        if (doc == null) return pomContent;

        XPathMatcher propertiesMatcher = new XPathMatcher(
                "/project/properties/" + propertyName);
        Xml.Document updated = (Xml.Document) new XmlVisitor<Integer>() {
            @Override
            public Xml.Tag visitTag(Xml.Tag tag, Integer ctx) {
                Xml.Tag t = (Xml.Tag) super.visitTag(tag, ctx);
                if (propertiesMatcher.matches(getCursor())) {
                    return t.withValue(newValue);
                }
                return t;
            }
        }.visitNonNull(doc, 0);

        return print(updated);
    }

    /**
     * Update the version of a specific plugin identified by
     * {@code groupId:artifactId} anywhere in the POM (both
     * {@code <plugins>} and {@code <pluginManagement>}).
     *
     * @param pomContent the raw POM text
     * @param groupId    plugin groupId to match
     * @param artifactId plugin artifactId to match
     * @param newVersion the version to set
     * @return updated POM text, or unchanged if no match
     */
    public static String updatePluginVersion(String pomContent,
                                              String groupId,
                                              String artifactId,
                                              String newVersion) {
        Xml.Document doc = parse(pomContent);
        if (doc == null) return pomContent;

        Xml.Document updated = (Xml.Document) new XmlVisitor<Integer>() {
            @Override
            public Xml.Tag visitTag(Xml.Tag tag, Integer ctx) {
                Xml.Tag t = (Xml.Tag) super.visitTag(tag, ctx);
                if (!"plugin".equals(t.getName())) return t;

                String gid = t.getChildValue("groupId").orElse(null);
                String aid = t.getChildValue("artifactId").orElse(null);
                if (groupId.equals(gid) && artifactId.equals(aid)) {
                    return t.withChildValue("version", newVersion);
                }
                return t;
            }
        }.visitNonNull(doc, 0);

        return print(updated);
    }

    /**
     * Remove the {@code <version>} child from a dependency matched by
     * {@code groupId:artifactId}. Used to eliminate intra-reactor
     * version pins where the reactor resolves the version automatically.
     *
     * @param pomContent the raw POM text
     * @param groupId    dependency groupId to match
     * @param artifactId dependency artifactId to match
     * @return updated POM text with version tag removed, or unchanged if no match
     */
    public static String removeDependencyVersion(String pomContent,
                                                  String groupId,
                                                  String artifactId) {
        Xml.Document doc = parse(pomContent);
        if (doc == null) return pomContent;

        Xml.Document updated = (Xml.Document) new XmlVisitor<Integer>() {
            @Override
            public Xml.Tag visitTag(Xml.Tag tag, Integer ctx) {
                Xml.Tag t = (Xml.Tag) super.visitTag(tag, ctx);
                if (!"dependency".equals(t.getName())) return t;

                String gid = t.getChildValue("groupId").orElse(null);
                String aid = t.getChildValue("artifactId").orElse(null);
                if (groupId.equals(gid) && artifactId.equals(aid)
                        && t.getChild("version").isPresent()) {
                    // Filter out the <version> element from content
                    var filtered = t.getContent().stream()
                            .filter(c -> !(c instanceof Xml.Tag child
                                    && "version".equals(child.getName())))
                            .toList();
                    return t.withContent(filtered);
                }
                return t;
            }
        }.visitNonNull(doc, 0);

        return print(updated);
    }

    /**
     * Add a property to the POM's {@code <properties>} block. No-op
     * if the property is already declared (use {@link #updateProperty}
     * to change an existing value). No-op if the POM has no
     * {@code <properties>} block.
     *
     * <p>Introduced for the {@code __ALIAS} indirection bake step
     * (IKE-Network/ike-issues#527): release-publish reads
     * {@code __ALIAS} declarations and materializes the corresponding
     * {@code <short>${canonical}</short>} indirections into the
     * release-tagged source pom.
     *
     * @param pomContent    the raw POM text
     * @param propertyName  the property name to add
     * @param propertyValue the property value
     * @return updated POM text, or unchanged if the property is
     *         already declared or no {@code <properties>} block exists
     */
    public static String addProperty(String pomContent,
                                      String propertyName,
                                      String propertyValue) {
        Xml.Document doc = parse(pomContent);
        if (doc == null) return pomContent;

        XPathMatcher propertiesMatcher = new XPathMatcher("/project/properties");

        Xml.Document updated = (Xml.Document) new XmlVisitor<Integer>() {
            @Override
            public Xml.Tag visitTag(Xml.Tag tag, Integer ctx) {
                Xml.Tag t = (Xml.Tag) super.visitTag(tag, ctx);
                if (propertiesMatcher.matches(getCursor())) {
                    if (t.getChild(propertyName).isPresent()) {
                        return t;
                    }
                    Xml.Tag newProp = Xml.Tag.build(
                            "<" + propertyName + ">" + propertyValue
                                    + "</" + propertyName + ">");
                    List<Content> newContent = new ArrayList<Content>(t.getContent());
                    newContent.add(newProp);
                    return t.withContent(newContent);
                }
                return t;
            }
        }.visitNonNull(doc, 0);

        return print(updated);
    }

    /**
     * Remove a property from the POM's {@code <properties>} block.
     * No-op if the property is not declared.
     *
     * <p>Introduced for the {@code __ALIAS} indirection unbake step
     * (IKE-Network/ike-issues#527): release-publish removes the
     * materialized indirections after the release tag, before the
     * merge back to main.
     *
     * @param pomContent   the raw POM text
     * @param propertyName the property name to remove
     * @return updated POM text, or unchanged if no match
     */
    public static String removeProperty(String pomContent,
                                         String propertyName) {
        Xml.Document doc = parse(pomContent);
        if (doc == null) return pomContent;

        XPathMatcher propertiesMatcher = new XPathMatcher("/project/properties");

        Xml.Document updated = (Xml.Document) new XmlVisitor<Integer>() {
            @Override
            public Xml.Tag visitTag(Xml.Tag tag, Integer ctx) {
                Xml.Tag t = (Xml.Tag) super.visitTag(tag, ctx);
                if (propertiesMatcher.matches(getCursor())) {
                    var filtered = t.getContent().stream()
                            .filter(c -> !(c instanceof Xml.Tag child
                                    && propertyName.equals(child.getName())))
                            .toList();
                    return t.withContent(filtered);
                }
                return t;
            }
        }.visitNonNull(doc, 0);

        return print(updated);
    }

    /**
     * List the properties declared in the POM's {@code <properties>}
     * block. Returns name → value pairs in declaration order.
     * Whitespace, comments, and non-tag content are skipped.
     *
     * <p>Used by the indirection bake/unbake steps
     * (IKE-Network/ike-issues#527) to scan for {@code __ALIAS}
     * declarations.
     *
     * @param pomContent the raw POM text
     * @return name → value map of declared properties; empty if no
     *         {@code <properties>} block exists
     */
    public static Map<String, String> listProperties(String pomContent) {
        Xml.Document doc = parse(pomContent);
        if (doc == null) return Map.of();

        Map<String, String> result = new LinkedHashMap<>();
        doc.getRoot().getChild("properties").ifPresent(props -> {
            for (Content c : props.getContent()) {
                if (c instanceof Xml.Tag child) {
                    String value = child.getValue().orElse("");
                    result.put(child.getName(), value);
                }
            }
        });
        return result;
    }

    /**
     * Parse POM content into an OpenRewrite XML document.
     *
     * @param pomContent the POM text
     * @return parsed document, or {@code null} if parsing failed
     */
    private static Xml.Document parse(String pomContent) {
        return PARSER.parse(pomContent)
                .findFirst()
                .map(t -> (Xml.Document) t)
                .orElse(null);
    }

    /**
     * Serialize an XML document back to a string.
     *
     * @param doc the parsed document
     * @return the document's textual form
     */
    private static String print(Xml.Document doc) {
        return doc.printAll();
    }
}
