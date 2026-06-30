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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Analyzes BOM imports in workspace subproject POMs to detect
 * version cascade gaps and support feature-start BOM updates.
 *
 * <p>Key concepts:
 * <ul>
 *   <li><strong>Workspace-internal BOM:</strong> a BOM (pom import)
 *       whose groupId:artifactId is in the published artifact set
 *       of a workspace subproject</li>
 *   <li><strong>External BOM:</strong> a BOM not published by any
 *       workspace subproject</li>
 *   <li><strong>Cascade gap:</strong> when subproject A depends on
 *       subproject B, but A has neither a version-property nor a
 *       workspace-internal BOM import that tracks B's version. A
 *       workspace BOM tracks B when it <em>is</em> B's own BOM, or when
 *       its {@code <dependencyManagement>} <em>manages</em> one of B's
 *       published artifacts (ike-issues#794)</li>
 *   <li><strong>External pin:</strong> an external BOM manages
 *       artifacts published by a workspace subproject, potentially
 *       overriding the workspace version</li>
 * </ul>
 */
public final class BomAnalysis {

    private BomAnalysis() {}

    /**
     * A BOM import found in a subproject's {@code <dependencyManagement>}.
     *
     * @param groupId    BOM groupId
     * @param artifactId BOM artifactId
     * @param version    declared version (may contain ${property} refs)
     * @param isWorkspaceInternal true if published by a workspace subproject
     * @param publishingSubproject name of the workspace subproject that
     *                            publishes this BOM (null if external)
     * @param orderIndex position in the import list (0-based, for
     *                   precedence analysis)
     */
    public record BomImport(String groupId, String artifactId, String version,
                            boolean isWorkspaceInternal,
                            String publishingSubproject,
                            int orderIndex) {}

    /**
     * A detected cascade issue for a subproject.
     *
     * @param subprojectName   the subproject with the issue
     * @param dependsOn        the upstream subproject it depends on
     * @param hasVersionProperty whether a version-property tracks upstream
     * @param hasWorkspaceBom  whether a workspace-internal BOM import covers
     *                         the edge — either it is the upstream's own BOM,
     *                         or it manages one of the upstream's published
     *                         artifacts (ike-issues#794)
     * @param externalBomPins  external BOMs that manage upstream's artifacts
     */
    public record CascadeIssue(String subprojectName, String dependsOn,
                                boolean hasVersionProperty,
                                boolean hasWorkspaceBom,
                                List<BomImport> externalBomPins) {

        /**
         * True if feature-start can cascade versions for this edge.
         *
         * @return true if a version-property or workspace BOM exists
         */
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
     * Extract all BOM imports from a subproject's root POM's
     * {@code <dependencyManagement>} section.
     *
     * @param pomFile the root POM to analyze
     * @param workspaceArtifacts map of workspace subproject name to
     *        its published artifact set
     * @return list of BOM imports in declaration order
     * @throws IOException if the POM cannot be read or parsed
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

                // Check if this BOM is published by a workspace subproject
                String publishingSubproject = null;
                for (Map.Entry<String, Set<PublishedArtifactSet.Artifact>> entry : workspaceArtifacts.entrySet()) {
                    for (PublishedArtifactSet.Artifact artifact : entry.getValue()) {
                        if (artifact.groupId().equals(gid)
                                && artifact.artifactId().equals(aid)) {
                            publishingSubproject = entry.getKey();
                            break;
                        }
                    }
                    if (publishingSubproject != null) break;
                }

                imports.add(new BomImport(gid, aid, ver,
                        publishingSubproject != null,
                        publishingSubproject, index));
                index++;
            }
        }

        return imports;
    }

    /**
     * Analyze cascade issues for all subprojects in the workspace.
     *
     * @param wsDir         workspace root directory
     * @param manifest      the workspace manifest
     * @param workspaceArtifacts published artifacts per subproject
     * @return list of cascade issues (empty if all edges can cascade)
     * @throws IOException if a subproject POM cannot be read
     */
    public static List<CascadeIssue> analyzeCascadeIssues(
            Path wsDir, Manifest manifest,
            Map<String, Set<PublishedArtifactSet.Artifact>> workspaceArtifacts)
            throws IOException {

        List<CascadeIssue> issues = new ArrayList<>();

        // Per-call cache of a workspace-internal BOM's managed artifact set,
        // keyed by the BOM's groupId:artifactId. A single shared BOM (e.g.
        // komet-bom) is imported by most subprojects, so resolving its
        // managed set once avoids re-walking and re-parsing it per edge.
        Map<String, Set<PublishedArtifactSet.Artifact>> managedCache =
                new LinkedHashMap<>();

        for (Map.Entry<String, Subproject> entry : manifest.subprojects().entrySet()) {
            String subprojectName = entry.getKey();
            Subproject sub = entry.getValue();

            if (sub.dependsOn() == null || sub.dependsOn().isEmpty()) continue;

            Path pomFile = wsDir.resolve(subprojectName).resolve("pom.xml");
            List<BomImport> bomImports = extractBomImports(
                    pomFile, workspaceArtifacts);

            for (Dependency dep : sub.dependsOn()) {
                String upstream = dep.subproject();
                boolean hasVersionProp = dep.versionProperty() != null;

                Set<PublishedArtifactSet.Artifact> upstreamArtifacts =
                        workspaceArtifacts.getOrDefault(upstream, Set.of());

                // A workspace-internal BOM covers this edge when it either
                // IS the upstream's own BOM, or it MANAGES one of the
                // upstream's published artifacts (ike-issues#794). The
                // latter is the common shape: a single shared BOM governs
                // every upstream's version and no per-edge version-property
                // is declared, so the structural "BOM GA == upstream GA"
                // test alone would report a false-positive gap.
                boolean hasWorkspaceBom = false;
                for (BomImport bom : bomImports) {
                    if (!bom.isWorkspaceInternal) continue;
                    if (upstream.equals(bom.publishingSubproject)
                            || bomManagesAny(wsDir, bom, upstreamArtifacts,
                                    managedCache)) {
                        hasWorkspaceBom = true;
                        break;
                    }
                }

                // Find external BOMs that may pin upstream's artifacts from
                // outside the workspace. Precisely confirming the pin would
                // require resolving the external BOM's effective
                // dependencyManagement — see the ike-issues#794 follow-up.
                List<BomImport> externalPins = new ArrayList<>();
                for (BomImport bom : bomImports) {
                    if (bom.isWorkspaceInternal) continue;
                    externalPins.add(bom);
                }

                if (!hasVersionProp && !hasWorkspaceBom) {
                    issues.add(new CascadeIssue(subprojectName, upstream,
                            hasVersionProp, hasWorkspaceBom, externalPins));
                }
            }
        }

        return issues;
    }

    /**
     * Extract the set of artifact coordinates managed by a BOM POM's
     * {@code <dependencyManagement>} section.
     *
     * <p>Returns every {@code groupId:artifactId} pair declared under
     * {@code <dependencyManagement><dependencies>}, with {@code ${property}}
     * references in the groupId and artifactId resolved against the POM's own
     * {@code <properties>}. Versions are ignored — this set answers "which
     * artifacts does this BOM govern", not "at what version".
     *
     * <p>Nested BOM imports (a {@code <dependency>} with {@code <type>pom</type>}
     * and {@code <scope>import</scope>}) are reported as their own coordinate
     * but are NOT transitively expanded into the artifacts they manage. See
     * the IKE-Network/ike-issues#794 follow-up for transitive resolution.
     *
     * @param bomPom the BOM POM file to read
     * @return the managed {@code groupId:artifactId} set; empty if the file is
     *         absent, unparseable, or declares no {@code <dependencyManagement>}
     * @throws IOException if the POM cannot be read
     */
    public static Set<PublishedArtifactSet.Artifact> extractManagedArtifacts(
            Path bomPom) throws IOException {
        Set<PublishedArtifactSet.Artifact> managed = new LinkedHashSet<>();
        if (!Files.exists(bomPom)) return managed;

        Document doc;
        try {
            DocumentBuilder db = DBF.newDocumentBuilder();
            doc = db.parse(bomPom.toFile());
        } catch (Exception e) {
            return managed;
        }

        Element project = doc.getDocumentElement();
        Map<String, String> properties = readProperties(project);

        Element depMgmt = firstChild(project, "dependencyManagement");
        if (depMgmt == null) return managed;
        Element deps = firstChild(depMgmt, "dependencies");
        if (deps == null) return managed;

        for (Element dep : children(deps, "dependency")) {
            String gid = resolve(childText(dep, "groupId"), properties);
            String aid = resolve(childText(dep, "artifactId"), properties);
            if (gid == null || aid == null) continue;
            managed.add(new PublishedArtifactSet.Artifact(gid, aid));
        }
        return managed;
    }

    /**
     * Whether a workspace-internal BOM import manages any of the upstream
     * subproject's published artifacts.
     *
     * @param wsDir             workspace root directory
     * @param bom               the workspace-internal BOM import to inspect
     * @param upstreamArtifacts the upstream subproject's published artifact set
     * @param cache             per-call cache of managed artifact sets keyed by
     *                          the BOM's {@code groupId:artifactId}
     * @return true if the BOM's managed set intersects the upstream's artifacts
     * @throws IOException if a POM cannot be read
     */
    private static boolean bomManagesAny(Path wsDir, BomImport bom,
            Set<PublishedArtifactSet.Artifact> upstreamArtifacts,
            Map<String, Set<PublishedArtifactSet.Artifact>> cache)
            throws IOException {
        if (upstreamArtifacts.isEmpty()) return false;
        Set<PublishedArtifactSet.Artifact> managed =
                managedArtifactsOf(wsDir, bom, cache);
        for (PublishedArtifactSet.Artifact artifact : upstreamArtifacts) {
            if (managed.contains(artifact)) return true;
        }
        return false;
    }

    /**
     * Resolve the managed artifact set of a workspace-internal BOM, caching
     * the result by the BOM's {@code groupId:artifactId}.
     *
     * <p>The BOM is published by {@link BomImport#publishingSubproject()}; its
     * declaring POM is located by walking that subproject's POM tree for the
     * file whose own coordinates equal the BOM's {@code groupId:artifactId}.
     * This is fully offline — workspace-internal BOMs are already on disk.
     *
     * @param wsDir workspace root directory
     * @param bom   the workspace-internal BOM import
     * @param cache per-call cache of managed sets keyed by BOM coordinate
     * @return the BOM's managed {@code groupId:artifactId} set (possibly empty)
     * @throws IOException if a POM cannot be read
     */
    private static Set<PublishedArtifactSet.Artifact> managedArtifactsOf(
            Path wsDir, BomImport bom,
            Map<String, Set<PublishedArtifactSet.Artifact>> cache)
            throws IOException {
        String key = bom.groupId() + ":" + bom.artifactId();
        Set<PublishedArtifactSet.Artifact> cached = cache.get(key);
        if (cached != null) return cached;

        Set<PublishedArtifactSet.Artifact> managed = new LinkedHashSet<>();
        if (bom.publishingSubproject() != null) {
            Path subDir = wsDir.resolve(bom.publishingSubproject());
            if (Files.exists(subDir)) {
                for (Path pom : findPomFiles(subDir)) {
                    if (projectGaMatches(pom, bom.groupId(), bom.artifactId())) {
                        managed.addAll(extractManagedArtifacts(pom));
                    }
                }
            }
        }
        cache.put(key, managed);
        return managed;
    }

    /**
     * Recursively collect {@code pom.xml} files under a directory, skipping
     * any {@code target/} build-output subtree.
     *
     * @param dir the directory to walk
     * @return the POM files found (empty if the directory does not exist)
     * @throws IOException if the directory cannot be walked
     */
    private static List<Path> findPomFiles(Path dir) throws IOException {
        if (!Files.exists(dir)) return List.of();
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> "pom.xml".equals(p.getFileName().toString()))
                    .filter(p -> !hasTargetSegment(p))
                    .toList();
        }
    }

    private static boolean hasTargetSegment(Path path) {
        for (Path segment : path) {
            if ("target".equals(segment.toString())) return true;
        }
        return false;
    }

    /**
     * Whether a POM's own project coordinates equal the given
     * {@code groupId:artifactId}. The groupId falls back to the parent's
     * groupId when not declared on the project itself.
     *
     * @param pomFile    the POM to inspect
     * @param groupId    the groupId to match
     * @param artifactId the artifactId to match
     * @return true if the POM declares (or inherits) the given coordinates
     */
    private static boolean projectGaMatches(Path pomFile, String groupId,
                                            String artifactId) {
        Document doc;
        try {
            DocumentBuilder db = DBF.newDocumentBuilder();
            doc = db.parse(pomFile.toFile());
        } catch (Exception e) {
            return false;
        }
        Element project = doc.getDocumentElement();
        if (!artifactId.equals(childText(project, "artifactId"))) return false;

        String gid = childText(project, "groupId");
        if (gid == null) {
            Element parent = firstChild(project, "parent");
            if (parent != null) gid = childText(parent, "groupId");
        }
        return groupId.equals(gid);
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
     * @throws IOException if the POM cannot be read or written
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
        for (Map.Entry<String, String> entry : properties.entrySet()) {
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
