package network.ike.workspace;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Scans a Maven component root to determine the complete set of
 * published artifacts (groupId:artifactId pairs).
 *
 * <p>Given a component root directory, recursively walks the POM
 * hierarchy (root POM plus all subprojects/modules) and collects
 * every groupId:artifactId pair that the component publishes.
 *
 * <p>POM parsing uses {@code javax.xml} DOM (built into the JDK).
 * Only direct children of {@code <project>} are examined for
 * coordinates, so dependency groupIds cannot be confused with the
 * project's own groupId.
 */
public final class PublishedArtifactSet {

    private PublishedArtifactSet() {}

    /**
     * A published Maven artifact coordinate.
     *
     * @param groupId    the Maven groupId
     * @param artifactId the Maven artifactId
     */
    public record Artifact(String groupId, String artifactId) {}

    private static final DocumentBuilderFactory DBF;
    static {
        DBF = DocumentBuilderFactory.newInstance();
        // Disable DTD/external entity loading for safety and speed
        try {
            DBF.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            DBF.setFeature("http://xml.org/sax/features/external-general-entities", false);
            DBF.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        } catch (ParserConfigurationException e) {
            // Non-fatal — factory will still work
        }
    }

    /**
     * Scan a component root and return the complete set of published
     * artifacts (groupId:artifactId pairs).
     *
     * <p>Reads the root pom.xml, extracts its coordinates, then
     * recursively descends into each subproject (or module) directory
     * to collect all published artifacts.
     *
     * @param componentRoot the root directory of the Maven component
     * @return the set of all published artifacts
     * @throws IOException if a POM file cannot be read or parsed
     */
    public static Set<Artifact> scan(Path componentRoot) throws IOException {
        Set<Artifact> artifacts = new LinkedHashSet<>();
        Path rootPom = componentRoot.resolve("pom.xml");

        if (!Files.exists(rootPom)) {
            return artifacts;
        }

        scanPom(rootPom, null, artifacts);
        return artifacts;
    }

    /**
     * Check whether a groupId:artifactId pair is in the published set.
     *
     * @param artifacts  the set from {@link #scan(Path)}
     * @param groupId    the groupId to check
     * @param artifactId the artifactId to check
     * @return true if the pair is in the set
     */
    public static boolean matches(Set<Artifact> artifacts,
                                  String groupId, String artifactId) {
        return artifacts.contains(new Artifact(groupId, artifactId));
    }

    /**
     * Parse a single POM via DOM, add its artifact to the set, then
     * recurse into any declared subprojects or modules.
     *
     * @param pomPath        the POM file to parse
     * @param inheritGroupId the parent groupId to inherit if not declared
     * @param artifacts      accumulator for discovered artifacts
     */
    private static void scanPom(Path pomPath, String inheritGroupId,
                                Set<Artifact> artifacts) throws IOException {
        Document doc;
        try {
            DocumentBuilder db = DBF.newDocumentBuilder();
            doc = db.parse(pomPath.toFile());
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("Cannot parse " + pomPath + ": " + e.getMessage(), e);
        }

        Element project = doc.getDocumentElement();

        // Extract parent groupId for inheritance
        String parentGroupId = null;
        Element parentEl = firstChildElement(project, "parent");
        if (parentEl != null) {
            parentGroupId = childText(parentEl, "groupId");
        }

        // Project's own groupId — direct child of <project>, not
        // from <parent> or <dependencies> or any nested block.
        String groupId = childText(project, "groupId");

        // Inherit: own → parent block → caller
        if (groupId == null) {
            groupId = parentGroupId;
        }
        if (groupId == null) {
            groupId = inheritGroupId;
        }

        String artifactId = childText(project, "artifactId");

        if (groupId != null && artifactId != null) {
            artifacts.add(new Artifact(groupId, artifactId));
        }

        String effectiveGroupId = groupId;
        Path pomDir = pomPath.getParent();

        // Recurse into <subprojects>/<subproject> (Maven 4.1.0)
        Element subprojects = firstChildElement(project, "subprojects");
        if (subprojects != null) {
            for (Element sub : childElements(subprojects, "subproject")) {
                String name = sub.getTextContent().trim();
                Path subPom = pomDir.resolve(name).resolve("pom.xml");
                if (Files.exists(subPom)) {
                    scanPom(subPom, effectiveGroupId, artifacts);
                }
            }
        }

        // Recurse into <modules>/<module> (Maven 4.0.0)
        Element modules = firstChildElement(project, "modules");
        if (modules != null) {
            for (Element mod : childElements(modules, "module")) {
                String name = mod.getTextContent().trim();
                Path modPom = pomDir.resolve(name).resolve("pom.xml");
                if (Files.exists(modPom)) {
                    scanPom(modPom, effectiveGroupId, artifacts);
                }
            }
        }
    }

    // ── DOM helpers ─────────────────────────────────────────────────

    /**
     * Get the text content of a direct child element, or null if
     * the child does not exist. Only examines direct children —
     * not descendants — so {@code childText(project, "groupId")}
     * returns the project's own groupId, never a dependency's.
     */
    private static String childText(Element parent, String tagName) {
        Element child = firstChildElement(parent, tagName);
        if (child == null) return null;
        String text = child.getTextContent().trim();
        return text.isEmpty() ? null : text;
    }

    /**
     * Get the first direct child element with the given tag name,
     * or null if none exists.
     */
    private static Element firstChildElement(Element parent, String tagName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE
                    && tagName.equals(node.getNodeName())) {
                return (Element) node;
            }
        }
        return null;
    }

    /**
     * Get all direct child elements with the given tag name.
     */
    private static Iterable<Element> childElements(Element parent,
                                                    String tagName) {
        java.util.List<Element> result = new java.util.ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE
                    && tagName.equals(node.getNodeName())) {
                result.add((Element) node);
            }
        }
        return result;
    }
}
