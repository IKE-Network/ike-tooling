package network.ike.workspace;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Analyzes BOM imports in workspace component POMs to detect
 * version cascade gaps and support feature-start BOM updates.
 *
 * <p>Key concepts:
 * <ul>
 *   <li><strong>Workspace-internal BOM:</strong> a BOM (pom import)
 *       whose groupId:artifactId is in the published artifact set
 *       of a workspace component</li>
 *   <li><strong>External BOM:</strong> a BOM not published by any
 *       workspace component</li>
 *   <li><strong>Cascade gap:</strong> when component A depends on
 *       component B, but A has no version-property or workspace-internal
 *       BOM import that tracks B's version</li>
 *   <li><strong>External pin:</strong> an external BOM manages
 *       artifacts published by a workspace component, potentially
 *       overriding the workspace version</li>
 * </ul>
 */
public final class BomAnalysis {

    private BomAnalysis() {}

    /**
     * A BOM import found in a component's {@code <dependencyManagement>}.
     *
     * @param groupId    BOM groupId
     * @param artifactId BOM artifactId
     * @param version    declared version (may contain ${property} refs)
     * @param isWorkspaceInternal true if published by a workspace component
     * @param publishingComponent name of the workspace component that
     *                            publishes this BOM (null if external)
     * @param orderIndex position in the import list (0-based, for
     *                   precedence analysis)
     */
    public record BomImport(String groupId, String artifactId, String version,
                            boolean isWorkspaceInternal,
                            String publishingComponent,
                            int orderIndex) {}

    /**
     * A detected cascade issue for a component.
     *
     * @param componentName    the component with the issue
     * @param dependsOn        the upstream component it depends on
     * @param hasVersionProperty whether a version-property tracks upstream
     * @param hasWorkspaceBom  whether a workspace-internal BOM import exists
     * @param externalBomPins  external BOMs that manage upstream's artifacts
     */
    public record CascadeIssue(String componentName, String dependsOn,
                                boolean hasVersionProperty,
                                boolean hasWorkspaceBom,
                                List<BomImport> externalBomPins) {

        /** True if feature-start can cascade versions for this edge. */
        public boolean canCascade() {
            return hasVersionProperty || hasWorkspaceBom;
        }
    }

    private static final DocumentBuilderFactory DBF;
    static {
        DBF = DocumentBuilderFactory.newInstance();
        try {
            DBF.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            DBF.setFeature("http://xml.org/sax/features/external-general-entities", false);
            DBF.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        } catch (Exception e) { /* non-fatal */ }
    }

    /**
     * Extract all BOM imports from a component's root POM's
     * {@code <dependencyManagement>} section.
     *
     * @param pomFile the root POM to analyze
     * @param workspaceArtifacts map of workspace component name to
     *        its published artifact set
     * @return list of BOM imports in declaration order
     */
    public static List<BomImport> extractBomImports(
            Path pomFile,
            Map<String, Set<PublishedArtifactSet.Artifact>> workspaceArtifacts)
            throws IOException {
        List<BomImport> imports = new ArrayList<>();
        if (!Files.exists(pomFile)) return imports;

        Document doc;
        try {
            DocumentBuilder db = DBF.newDocumentBuilder();
            doc = db.parse(pomFile.toFile());
        } catch (Exception e) {
            return imports;
        }

        Element project = doc.getDocumentElement();

        // Read properties for ${...} resolution
        Map<String, String> properties = readProperties(project);

        Element depMgmt = firstChild(project, "dependencyManagement");
        if (depMgmt == null) return imports;

        Element deps = firstChild(depMgmt, "dependencies");
        if (deps == null) return imports;

        int index = 0;
        for (Element dep : children(deps, "dependency")) {
            String type = childText(dep, "type");
            String scope = childText(dep, "scope");

            if ("pom".equals(type) && "import".equals(scope)) {
                String gid = resolve(childText(dep, "groupId"), properties);
                String aid = resolve(childText(dep, "artifactId"), properties);
                String ver = resolve(childText(dep, "version"), properties);

                if (gid == null || aid == null) continue;

                // Check if this BOM is published by a workspace component
                String publishingComponent = null;
                for (var entry : workspaceArtifacts.entrySet()) {
                    for (var artifact : entry.getValue()) {
                        if (artifact.groupId().equals(gid)
                                && artifact.artifactId().equals(aid)) {
                            publishingComponent = entry.getKey();
                            break;
                        }
                    }
                    if (publishingComponent != null) break;
                }

                imports.add(new BomImport(gid, aid, ver,
                        publishingComponent != null,
                        publishingComponent, index));
                index++;
            }
        }

        return imports;
    }

    /**
     * Analyze cascade issues for all components in the workspace.
     *
     * @param wsDir         workspace root directory
     * @param manifest      the workspace manifest
     * @param workspaceArtifacts published artifacts per component
     * @return list of cascade issues (empty if all edges can cascade)
     */
    public static List<CascadeIssue> analyzeCascadeIssues(
            Path wsDir, Manifest manifest,
            Map<String, Set<PublishedArtifactSet.Artifact>> workspaceArtifacts)
            throws IOException {

        List<CascadeIssue> issues = new ArrayList<>();

        for (var entry : manifest.components().entrySet()) {
            String compName = entry.getKey();
            Component comp = entry.getValue();

            if (comp.dependsOn() == null || comp.dependsOn().isEmpty()) continue;

            Path pomFile = wsDir.resolve(compName).resolve("pom.xml");
            List<BomImport> bomImports = extractBomImports(
                    pomFile, workspaceArtifacts);

            for (Dependency dep : comp.dependsOn()) {
                String upstream = dep.component();
                boolean hasVersionProp = dep.versionProperty() != null;

                // Check if any workspace-internal BOM import tracks upstream
                boolean hasWorkspaceBom = bomImports.stream()
                        .anyMatch(b -> b.isWorkspaceInternal
                                && upstream.equals(b.publishingComponent));

                // Find external BOMs that manage upstream's artifacts
                Set<PublishedArtifactSet.Artifact> upstreamArtifacts =
                        workspaceArtifacts.getOrDefault(upstream, Set.of());
                List<BomImport> externalPins = new ArrayList<>();

                for (BomImport bom : bomImports) {
                    if (bom.isWorkspaceInternal) continue;

                    // We can't resolve the BOM's managed deps without
                    // downloading it. But we can flag external BOMs that
                    // share a groupId prefix with upstream artifacts as
                    // potential pins.
                    // For a more precise check, we'd need the BOM's
                    // effective dependencyManagement — future enhancement.
                    externalPins.add(bom);
                }

                if (!hasVersionProp && !hasWorkspaceBom) {
                    issues.add(new CascadeIssue(compName, upstream,
                            hasVersionProp, hasWorkspaceBom, externalPins));
                }
            }
        }

        return issues;
    }

    /**
     * Update a BOM import version in a POM file.
     * Finds the {@code <dependency>} block in {@code <dependencyManagement>}
     * with matching groupId:artifactId and type=pom/scope=import,
     * and rewrites its {@code <version>} element.
     *
     * @param pomFile    the POM to modify
     * @param groupId    BOM groupId to match
     * @param artifactId BOM artifactId to match
     * @param newVersion the new version to set
     * @return true if the file was modified
     */
    public static boolean updateBomImportVersion(Path pomFile,
                                                  String groupId,
                                                  String artifactId,
                                                  String newVersion)
            throws IOException {
        String content = Files.readString(pomFile);
        String original = content;

        // Find the BOM import block and update its version.
        // This uses text manipulation (not DOM) because we need to
        // preserve the exact formatting of the POM.
        //
        // Strategy: find <dependency> blocks inside <dependencyManagement>
        // that match groupId, artifactId, type=pom, scope=import,
        // then rewrite the <version> element.
        String depMgmtBlock = extractBlock(content,
                "<dependencyManagement>", "</dependencyManagement>");
        if (depMgmtBlock == null) return false;

        String searchGid = "<groupId>" + groupId + "</groupId>";
        String searchAid = "<artifactId>" + artifactId + "</artifactId>";

        int searchFrom = 0;
        while (true) {
            int depStart = depMgmtBlock.indexOf("<dependency>", searchFrom);
            if (depStart < 0) break;
            int depEnd = depMgmtBlock.indexOf("</dependency>", depStart);
            if (depEnd < 0) break;

            String depBlock = depMgmtBlock.substring(depStart, depEnd + "</dependency>".length());
            searchFrom = depEnd + 1;

            if (!depBlock.contains(searchGid) || !depBlock.contains(searchAid)) continue;
            if (!depBlock.contains("<type>pom</type>")) continue;
            if (!depBlock.contains("<scope>import</scope>")) continue;

            // Found the matching BOM import — update its version
            String versionPattern = "<version>[^<]+</version>";
            String updatedBlock = depBlock.replaceFirst(
                    versionPattern, "<version>" + newVersion + "</version>");

            if (!updatedBlock.equals(depBlock)) {
                content = content.replace(depBlock, updatedBlock);
                Files.writeString(pomFile, content);
                return true;
            }
        }

        return false;
    }

    // ── DOM helpers (same pattern as PublishedArtifactSet) ──────

    private static String extractBlock(String content, String startTag, String endTag) {
        int start = content.indexOf(startTag);
        if (start < 0) return null;
        int end = content.indexOf(endTag, start);
        if (end < 0) return null;
        return content.substring(start, end + endTag.length());
    }

    private static Map<String, String> readProperties(Element project) {
        Map<String, String> props = new LinkedHashMap<>();
        Element propsEl = firstChild(project, "properties");
        if (propsEl != null) {
            NodeList children = propsEl.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node node = children.item(i);
                if (node.getNodeType() == Node.ELEMENT_NODE) {
                    String value = node.getTextContent().trim();
                    if (!value.isEmpty()) {
                        props.put(node.getNodeName(), value);
                    }
                }
            }
        }
        return props;
    }

    private static String resolve(String value, Map<String, String> properties) {
        if (value == null || !value.contains("${")) return value;
        for (var entry : properties.entrySet()) {
            value = value.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        return value;
    }

    private static String childText(Element parent, String tagName) {
        Element child = firstChild(parent, tagName);
        if (child == null) return null;
        String text = child.getTextContent().trim();
        return text.isEmpty() ? null : text;
    }

    private static Element firstChild(Element parent, String tagName) {
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

    private static List<Element> children(Element parent, String tagName) {
        List<Element> result = new ArrayList<>();
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
