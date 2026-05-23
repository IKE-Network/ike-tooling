package network.ike.workspace.cascade;

import org.apache.maven.api.model.Build;
import org.apache.maven.api.model.Dependency;
import org.apache.maven.api.model.DependencyManagement;
import org.apache.maven.api.model.Model;
import org.apache.maven.api.model.Parent;
import org.apache.maven.api.model.Plugin;
import org.apache.maven.api.model.PluginManagement;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Derives the {@link CascadeEdge}s a project radiates upstream from
 * its Maven model and on-disk layout (IKE-Network/ike-issues#496
 * part B).
 *
 * <p>Replaces the hand-authored {@code release-cascade.yaml} as the
 * source of an IKE project's upstream edges. The cascade specifies
 * every version-bearing site in an IKE POM as a potential edge, not
 * just {@code <dependencies>}: parent inheritance, plain
 * dependencies, dependency-management entries (including imported
 * BOMs), plugins, plugin management, and {@code .mvn/extensions.xml}.
 * Under the {@code ${G·A}} property convention the property name an
 * alignment step rewrites is mechanical
 * ({@link CascadeEdge#versionProperty()}), so no manifest field
 * declares it — the coordinate is enough.
 *
 * <p>The deriver emits an edge only for coordinates a
 * {@link CoordinateFilter} accepts; the default filter
 * ({@link CoordinateFilter#IKE_GROUP}) keeps edges whose
 * {@code groupId} starts with {@code network.ike}. Third-party
 * dependencies stay out of the graph: IKE does not release them, so
 * they have no place in a release ordering.
 *
 * <p>A coordinate must bear a {@code <version>} at its site to count.
 * A {@code <dependency>} that inherits its version from
 * {@code <dependencyManagement>} contributes no edge from the
 * dependency site itself; the contributing edge sits at the
 * {@code <dependencyManagement>} entry instead.
 *
 * <p>Self-edges — where a coordinate's reactor-root repository is the
 * same repository as the POM the deriver is scanning — are <em>not</em>
 * filtered here. The deriver knows the POM's coordinates but not its
 * {@code <scm>}, and the same repository can publish many coordinates.
 * Self-edge filtering happens after the {@code <scm>}-keyed node
 * resolution in IKE-Network/ike-issues#496 part D.
 */
public final class PomEdgeDeriver {

    /**
     * A predicate over a Maven coordinate that selects which edges
     * the deriver should emit. {@link #IKE_GROUP} is the IKE
     * default.
     */
    @FunctionalInterface
    public interface CoordinateFilter {

        /**
         * Tests whether a {@code groupId} / {@code artifactId} pair
         * should produce an edge.
         *
         * @param groupId    the coordinate's {@code groupId}; may be
         *                   {@code null}
         * @param artifactId the coordinate's {@code artifactId}; may
         *                   be {@code null}
         * @return {@code true} iff an edge should be emitted
         */
        boolean accepts(String groupId, String artifactId);

        /**
         * Keeps coordinates whose {@code groupId} starts with
         * {@code "network.ike"}. The conventional filter for IKE
         * Network projects.
         */
        CoordinateFilter IKE_GROUP = (groupId, artifactId) ->
                groupId != null && groupId.startsWith("network.ike");
    }

    /** Conventional path of a Maven 4 build-extensions descriptor. */
    public static final String EXTENSIONS_RELATIVE_PATH =
            ".mvn/extensions.xml";

    private static final QName EXTENSION_QNAME =
            new QName("extension");
    private static final QName GROUP_ID_QNAME = new QName("groupId");
    private static final QName ARTIFACT_ID_QNAME =
            new QName("artifactId");
    private static final QName VERSION_QNAME = new QName("version");

    private PomEdgeDeriver() {}

    /**
     * Derives the upstream edges of a project from its model and
     * project directory.
     *
     * @param model      the project's Maven model (typically the file
     *                   model, but any stage works — the deriver only
     *                   reads structural fields)
     * @param projectDir the project's on-disk root directory, used to
     *                   locate {@code .mvn/extensions.xml}; may be
     *                   {@code null} if the caller knows the project
     *                   has no extensions descriptor
     * @return the derived upstream edges, in the order the sites
     *         appear in the POM (parent, dependencies, depMgmt,
     *         plugins, pluginMgmt, extensions); never {@code null}
     */
    public static List<CascadeEdge> deriveEdges(Model model,
                                                Path projectDir) {
        return deriveEdges(model, projectDir, CoordinateFilter.IKE_GROUP);
    }

    /**
     * Derives upstream edges with a caller-supplied coordinate
     * filter.
     *
     * @param model      the project's Maven model
     * @param projectDir the project's on-disk root directory; may be
     *                   {@code null}
     * @param filter     selects which coordinates produce edges; must
     *                   not be {@code null}
     * @return the derived upstream edges, in source-order; never
     *         {@code null}
     */
    public static List<CascadeEdge> deriveEdges(Model model,
                                                Path projectDir,
                                                CoordinateFilter filter) {
        if (model == null) {
            throw new IllegalArgumentException("model is required");
        }
        if (filter == null) {
            throw new IllegalArgumentException("filter is required");
        }
        List<CascadeEdge> edges = new ArrayList<>();
        appendParentEdge(model, filter, edges);
        appendDependencyEdges(model, filter, edges);
        appendDependencyManagementEdges(model, filter, edges);
        appendPluginEdges(model, filter, edges);
        appendExtensionEdges(projectDir, filter, edges);
        return List.copyOf(edges);
    }

    private static void appendParentEdge(Model model,
                                          CoordinateFilter filter,
                                          List<CascadeEdge> out) {
        Parent parent = model.getParent();
        if (parent == null) {
            return;
        }
        // Maven enforces a version on <parent>; a null/blank here would
        // be a malformed POM and the model layer would have rejected it.
        if (!filter.accepts(parent.getGroupId(), parent.getArtifactId())) {
            return;
        }
        out.add(edge(parent.getGroupId(), parent.getArtifactId(),
                EdgeKind.PARENT));
    }

    private static void appendDependencyEdges(Model model,
                                               CoordinateFilter filter,
                                               List<CascadeEdge> out) {
        List<Dependency> deps = model.getDependencies();
        if (deps == null) {
            return;
        }
        for (Dependency dep : deps) {
            if (!hasVersion(dep.getVersion())) {
                // Inherits its version from <dependencyManagement>;
                // the contributing edge is at the depMgmt site, not
                // here.
                continue;
            }
            if (!filter.accepts(dep.getGroupId(), dep.getArtifactId())) {
                continue;
            }
            out.add(edge(dep.getGroupId(), dep.getArtifactId(),
                    EdgeKind.DEPENDENCY));
        }
    }

    private static void appendDependencyManagementEdges(
            Model model, CoordinateFilter filter,
            List<CascadeEdge> out) {
        DependencyManagement dm = model.getDependencyManagement();
        if (dm == null || dm.getDependencies() == null) {
            return;
        }
        for (Dependency dep : dm.getDependencies()) {
            if (!hasVersion(dep.getVersion())) {
                continue;
            }
            if (!filter.accepts(dep.getGroupId(), dep.getArtifactId())) {
                continue;
            }
            EdgeKind kind = "import".equalsIgnoreCase(dep.getScope())
                    ? EdgeKind.BOM
                    : EdgeKind.DEPENDENCY;
            out.add(edge(dep.getGroupId(), dep.getArtifactId(), kind));
        }
    }

    private static void appendPluginEdges(Model model,
                                           CoordinateFilter filter,
                                           List<CascadeEdge> out) {
        Build build = model.getBuild();
        if (build == null) {
            return;
        }
        appendPlugins(build.getPlugins(), filter, out);
        PluginManagement pm = build.getPluginManagement();
        if (pm != null) {
            appendPlugins(pm.getPlugins(), filter, out);
        }
    }

    private static void appendPlugins(List<Plugin> plugins,
                                       CoordinateFilter filter,
                                       List<CascadeEdge> out) {
        if (plugins == null) {
            return;
        }
        for (Plugin plugin : plugins) {
            if (!hasVersion(plugin.getVersion())) {
                continue;
            }
            // A plugin's groupId can default to
            // org.apache.maven.plugins when absent; for our purposes
            // an unspecified groupId is third-party so we treat it
            // as not-IKE and skip.
            if (!filter.accepts(plugin.getGroupId(),
                    plugin.getArtifactId())) {
                continue;
            }
            out.add(edge(plugin.getGroupId(), plugin.getArtifactId(),
                    EdgeKind.PLUGIN));
        }
    }

    private static void appendExtensionEdges(Path projectDir,
                                              CoordinateFilter filter,
                                              List<CascadeEdge> out) {
        if (projectDir == null) {
            return;
        }
        Path extensionsXml = projectDir.resolve(EXTENSIONS_RELATIVE_PATH);
        if (!Files.isRegularFile(extensionsXml)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(
                extensionsXml, StandardCharsets.UTF_8)) {
            readExtensions(reader, filter, out);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Cannot read " + extensionsXml, e);
        } catch (XMLStreamException e) {
            throw new IllegalStateException(
                    "Malformed " + extensionsXml + ": "
                    + e.getMessage(), e);
        }
    }

    private static void readExtensions(Reader source,
                                        CoordinateFilter filter,
                                        List<CascadeEdge> out)
            throws XMLStreamException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE,
                Boolean.FALSE);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD,
                Boolean.FALSE);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities",
                Boolean.FALSE);
        XMLStreamReader xml = factory.createXMLStreamReader(source);
        try {
            String groupId = null;
            String artifactId = null;
            String version = null;
            String currentLeaf = null;
            boolean insideExtension = false;
            StringBuilder leafText = new StringBuilder();

            while (xml.hasNext()) {
                int event = xml.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String name = xml.getLocalName();
                    if (EXTENSION_QNAME.getLocalPart().equals(name)) {
                        insideExtension = true;
                        groupId = null;
                        artifactId = null;
                        version = null;
                    } else if (insideExtension && (
                            GROUP_ID_QNAME.getLocalPart().equals(name)
                            || ARTIFACT_ID_QNAME.getLocalPart().equals(name)
                            || VERSION_QNAME.getLocalPart().equals(name))) {
                        currentLeaf = name;
                        leafText.setLength(0);
                    }
                } else if (event == XMLStreamConstants.CHARACTERS
                        && currentLeaf != null) {
                    leafText.append(xml.getText());
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    String name = xml.getLocalName();
                    if (currentLeaf != null && currentLeaf.equals(name)) {
                        String value = leafText.toString().trim();
                        switch (currentLeaf) {
                            case "groupId" -> groupId = value;
                            case "artifactId" -> artifactId = value;
                            case "version" -> version = value;
                            default -> { /* ignored */ }
                        }
                        currentLeaf = null;
                    } else if (EXTENSION_QNAME.getLocalPart().equals(name)
                            && insideExtension) {
                        if (hasVersion(version)
                                && filter.accepts(groupId, artifactId)) {
                            out.add(edge(groupId, artifactId,
                                    EdgeKind.EXTENSION));
                        }
                        insideExtension = false;
                    }
                }
            }
        } finally {
            xml.close();
        }
    }

    /**
     * An identity-only edge for a derived upstream — the deriver does
     * not know the upstream's on-disk {@code repo} name or git URL
     * (those come from the {@code <scm>}-keyed node resolution in
     * IKE-Network/ike-issues#496 part C).
     */
    private static CascadeEdge edge(String groupId, String artifactId,
                                    EdgeKind kind) {
        return new CascadeEdge(groupId, artifactId, null, null, kind);
    }

    /**
     * A version field counts if it is non-null and non-blank. The
     * value need not be a literal; {@code ${G·A}} placeholders
     * count as "version-bearing" because they declare an upstream
     * pin even if interpolation has not happened yet.
     */
    private static boolean hasVersion(String version) {
        return version != null && !version.isBlank();
    }
}
