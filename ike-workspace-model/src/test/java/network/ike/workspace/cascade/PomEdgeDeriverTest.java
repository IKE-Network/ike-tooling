package network.ike.workspace.cascade;

import org.apache.maven.api.model.Build;
import org.apache.maven.api.model.Dependency;
import org.apache.maven.api.model.DependencyManagement;
import org.apache.maven.api.model.Model;
import org.apache.maven.api.model.Parent;
import org.apache.maven.api.model.Plugin;
import org.apache.maven.api.model.PluginManagement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Tests for {@link PomEdgeDeriver}: deriving cascade edges from every
 * version-bearing site in a Maven model
 * (IKE-Network/ike-issues#496 part B).
 */
class PomEdgeDeriverTest {

    @Test
    void parent_emits_a_parent_edge_when_ike_managed() {
        Model model = Model.newBuilder()
                .groupId("network.ike.examples")
                .artifactId("workspace-example")
                .version("1-SNAPSHOT")
                .parent(Parent.newBuilder()
                        .groupId("network.ike.platform")
                        .artifactId("ike-parent")
                        .version("79")
                        .build())
                .build();

        List<CascadeEdge> edges = PomEdgeDeriver.deriveEdges(model, null);

        assertThat(edges).singleElement().satisfies(e -> {
            assertThat(e.ga())
                    .isEqualTo("network.ike.platform:ike-parent");
            assertThat(e.kind()).isEqualTo(EdgeKind.PARENT);
        });
    }

    @Test
    void parent_outside_ike_groupId_emits_no_edge() {
        Model model = Model.newBuilder()
                .groupId("example")
                .artifactId("third-party-consumer")
                .version("1-SNAPSHOT")
                .parent(Parent.newBuilder()
                        .groupId("org.example")
                        .artifactId("example-parent")
                        .version("7")
                        .build())
                .build();

        assertThat(PomEdgeDeriver.deriveEdges(model, null)).isEmpty();
    }

    @Test
    void direct_dependency_with_a_version_emits_a_dependency_edge() {
        Model model = Model.newBuilder()
                .groupId("network.ike.examples")
                .artifactId("project-example")
                .version("1-SNAPSHOT")
                .dependencies(List.of(
                        Dependency.newBuilder()
                                .groupId("network.ike.docs")
                                .artifactId("ike-doc-resources")
                                .version("${network.ike.docs·ike-docs}")
                                .build(),
                        // Third-party — filtered out.
                        Dependency.newBuilder()
                                .groupId("org.junit.jupiter")
                                .artifactId("junit-jupiter")
                                .version("6.0.0")
                                .scope("test")
                                .build()))
                .build();

        List<CascadeEdge> edges = PomEdgeDeriver.deriveEdges(model, null);

        assertThat(edges)
                .extracting(CascadeEdge::ga, CascadeEdge::kind)
                .containsExactly(tuple("network.ike.docs:ike-doc-resources",
                        EdgeKind.DEPENDENCY));
    }

    @Test
    void dependency_without_a_version_emits_no_edge() {
        // A <dependency> with no <version> inherits from
        // <dependencyManagement>; the depMgmt entry is the contributing
        // site, not the dependency itself.
        Model model = Model.newBuilder()
                .groupId("network.ike.examples")
                .artifactId("project-example")
                .version("1-SNAPSHOT")
                .dependencies(List.of(
                        Dependency.newBuilder()
                                .groupId("network.ike.docs")
                                .artifactId("ike-doc-resources")
                                .build()))
                .build();

        assertThat(PomEdgeDeriver.deriveEdges(model, null)).isEmpty();
    }

    @Test
    void dependency_management_entries_emit_dependency_or_bom_edges() {
        Model model = Model.newBuilder()
                .groupId("network.ike.examples")
                .artifactId("workspace-example")
                .version("1-SNAPSHOT")
                .dependencyManagement(DependencyManagement.newBuilder()
                        .dependencies(List.of(
                                // Plain managed dependency.
                                Dependency.newBuilder()
                                        .groupId("network.ike.tooling")
                                        .artifactId("ike-maven-plugin-support")
                                        .version("199")
                                        .build(),
                                // Imported BOM — emits a BOM edge.
                                Dependency.newBuilder()
                                        .groupId("network.ike.platform")
                                        .artifactId("ike-bom")
                                        .version("80")
                                        .type("pom")
                                        .scope("import")
                                        .build()))
                        .build())
                .build();

        List<CascadeEdge> edges = PomEdgeDeriver.deriveEdges(model, null);

        assertThat(edges)
                .extracting(CascadeEdge::ga, CascadeEdge::kind)
                .containsExactly(
                        tuple("network.ike.tooling:ike-maven-plugin-support",
                                EdgeKind.DEPENDENCY),
                        tuple("network.ike.platform:ike-bom",
                                EdgeKind.BOM));
    }

    @Test
    void plugin_and_pluginManagement_emit_plugin_edges() {
        Model model = Model.newBuilder()
                .groupId("network.ike.examples")
                .artifactId("workspace-example")
                .version("1-SNAPSHOT")
                .build(Build.newBuilder()
                        .plugins(List.of(
                                Plugin.newBuilder()
                                        .groupId("network.ike.tooling")
                                        .artifactId("ike-maven-plugin")
                                        .version("${network.ike.tooling·ike-tooling}")
                                        .build()))
                        .pluginManagement(PluginManagement.newBuilder()
                                .plugins(List.of(
                                        Plugin.newBuilder()
                                                .groupId("network.ike.docs")
                                                .artifactId("ike-doc-maven-plugin")
                                                .version("${network.ike.docs·ike-docs}")
                                                .build()))
                                .build())
                        .build())
                .build();

        List<CascadeEdge> edges = PomEdgeDeriver.deriveEdges(model, null);

        assertThat(edges)
                .extracting(CascadeEdge::ga, CascadeEdge::kind)
                .containsExactly(
                        tuple("network.ike.tooling:ike-maven-plugin",
                                EdgeKind.PLUGIN),
                        tuple("network.ike.docs:ike-doc-maven-plugin",
                                EdgeKind.PLUGIN));
    }

    @Test
    void unversioned_plugin_is_skipped() {
        Model model = Model.newBuilder()
                .groupId("network.ike.examples")
                .artifactId("workspace-example")
                .version("1-SNAPSHOT")
                .build(Build.newBuilder()
                        .plugins(List.of(
                                // No <version> — version comes from
                                // pluginManagement upstream. No edge
                                // from this site.
                                Plugin.newBuilder()
                                        .groupId("network.ike.tooling")
                                        .artifactId("ike-maven-plugin")
                                        .build()))
                        .build())
                .build();

        assertThat(PomEdgeDeriver.deriveEdges(model, null)).isEmpty();
    }

    @Test
    void mvn_extensions_xml_emits_extension_edges(@TempDir Path tmp)
            throws IOException {
        Path projectDir = tmp.resolve("workspace-example");
        Path extensionsXml = projectDir.resolve(
                PomEdgeDeriver.EXTENSIONS_RELATIVE_PATH);
        Files.createDirectories(extensionsXml.getParent());
        Files.writeString(extensionsXml, """
                <?xml version="1.0" encoding="UTF-8"?>
                <extensions xmlns="http://maven.apache.org/EXTENSIONS/1.2.0">
                    <extension>
                        <groupId>network.ike.tooling</groupId>
                        <artifactId>ike-workspace-extension</artifactId>
                        <version>${network.ike.tooling·ike-workspace-extension}</version>
                    </extension>
                    <extension>
                        <groupId>network.ike.tooling</groupId>
                        <artifactId>ike-version-management-extension</artifactId>
                        <version>1</version>
                    </extension>
                    <!-- Third-party: filtered out by IKE_GROUP. -->
                    <extension>
                        <groupId>org.apache.maven.wagon</groupId>
                        <artifactId>wagon-ssh</artifactId>
                        <version>3.5.3</version>
                    </extension>
                </extensions>
                """, StandardCharsets.UTF_8);

        Model model = Model.newBuilder()
                .groupId("network.ike.examples")
                .artifactId("workspace-example")
                .version("1-SNAPSHOT")
                .build();

        List<CascadeEdge> edges = PomEdgeDeriver.deriveEdges(
                model, projectDir);

        assertThat(edges)
                .extracting(CascadeEdge::ga, CascadeEdge::kind)
                .containsExactly(
                        tuple("network.ike.tooling:ike-workspace-extension",
                                EdgeKind.EXTENSION),
                        tuple("network.ike.tooling:ike-version-management-extension",
                                EdgeKind.EXTENSION));
    }

    @Test
    void missing_extensions_xml_is_a_no_op(@TempDir Path tmp) {
        Model model = Model.newBuilder()
                .groupId("network.ike.examples")
                .artifactId("workspace-example")
                .version("1-SNAPSHOT")
                .build();

        assertThat(PomEdgeDeriver.deriveEdges(model, tmp)).isEmpty();
    }

    @Test
    void edges_emit_in_source_order_across_sites(@TempDir Path tmp)
            throws IOException {
        // parent → dependencies → depMgmt → plugins → pluginMgmt →
        // extensions, in source-order within each site.
        Path projectDir = tmp.resolve("ike-platform");
        Path extensionsXml = projectDir.resolve(
                PomEdgeDeriver.EXTENSIONS_RELATIVE_PATH);
        Files.createDirectories(extensionsXml.getParent());
        Files.writeString(extensionsXml, """
                <?xml version="1.0" encoding="UTF-8"?>
                <extensions>
                    <extension>
                        <groupId>network.ike.tooling</groupId>
                        <artifactId>ike-workspace-extension</artifactId>
                        <version>4</version>
                    </extension>
                </extensions>
                """, StandardCharsets.UTF_8);

        Model model = Model.newBuilder()
                .groupId("network.ike.platform")
                .artifactId("ike-platform")
                .version("80-SNAPSHOT")
                .parent(Parent.newBuilder()
                        .groupId("network.ike")
                        .artifactId("ike-base-parent")
                        .version("7")
                        .build())
                .dependencies(List.of(
                        Dependency.newBuilder()
                                .groupId("network.ike.docs")
                                .artifactId("koncept-asciidoc-extension")
                                .version("52")
                                .build()))
                .dependencyManagement(DependencyManagement.newBuilder()
                        .dependencies(List.of(
                                Dependency.newBuilder()
                                        .groupId("network.ike.tooling")
                                        .artifactId("ike-build-standards")
                                        .version("198")
                                        .build()))
                        .build())
                .build(Build.newBuilder()
                        .plugins(List.of(
                                Plugin.newBuilder()
                                        .groupId("network.ike.tooling")
                                        .artifactId("ike-maven-plugin")
                                        .version("198")
                                        .build()))
                        .pluginManagement(PluginManagement.newBuilder()
                                .plugins(List.of(
                                        Plugin.newBuilder()
                                                .groupId("network.ike.docs")
                                                .artifactId("ike-doc-maven-plugin")
                                                .version("52")
                                                .build()))
                                .build())
                        .build())
                .build();

        List<String> gas = PomEdgeDeriver.deriveEdges(model, projectDir)
                .stream().map(CascadeEdge::ga).toList();

        assertThat(gas).containsExactly(
                "network.ike:ike-base-parent",
                "network.ike.docs:koncept-asciidoc-extension",
                "network.ike.tooling:ike-build-standards",
                "network.ike.tooling:ike-maven-plugin",
                "network.ike.docs:ike-doc-maven-plugin",
                "network.ike.tooling:ike-workspace-extension");
    }

    @Test
    void custom_coordinate_filter_overrides_the_ike_default() {
        Model model = Model.newBuilder()
                .groupId("example")
                .artifactId("third-party-consumer")
                .version("1-SNAPSHOT")
                .dependencies(List.of(
                        Dependency.newBuilder()
                                .groupId("org.example")
                                .artifactId("example-lib")
                                .version("3.14")
                                .build()))
                .build();

        PomEdgeDeriver.CoordinateFilter acceptAll =
                (groupId, artifactId) -> true;
        List<CascadeEdge> edges = PomEdgeDeriver.deriveEdges(
                model, null, acceptAll);

        assertThat(edges).extracting(CascadeEdge::ga)
                .containsExactly("org.example:example-lib");
    }

    @Test
    void null_model_or_filter_is_rejected() {
        Stream.of(
                (Runnable) () -> PomEdgeDeriver.deriveEdges(null, null),
                (Runnable) () -> PomEdgeDeriver.deriveEdges(
                        Model.newBuilder().build(), null, null)
        ).forEach(call -> assertThatThrownBy(call::run)
                .isInstanceOf(IllegalArgumentException.class));
    }
}
