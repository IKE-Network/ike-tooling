package network.ike.workspace;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkspaceGraphTest {

    private static WorkspaceGraph graph;

    @BeforeAll
    static void loadGraph() {
        Path path = Path.of("src/test/resources/workspace.yaml");
        Manifest manifest = ManifestReader.read(path);
        graph = new WorkspaceGraph(manifest);
    }

    // ── Topological Sort ────────────────────────────────────────────

    @Test
    void topologicalSortPutsDependenciesFirst() {
        List<String> sorted = graph.topologicalSort();

        // ike-bom must come before tinkar-core (tinkar depends on bom)
        assertThat(sorted.indexOf("ike-bom"))
                .isLessThan(sorted.indexOf("tinkar-core"));

        // tinkar-core before komet
        assertThat(sorted.indexOf("tinkar-core"))
                .isLessThan(sorted.indexOf("komet"));

        // komet before komet-desktop
        assertThat(sorted.indexOf("komet"))
                .isLessThan(sorted.indexOf("komet-desktop"));

        // ike-pipeline before ike-lab-documents
        assertThat(sorted.indexOf("ike-pipeline"))
                .isLessThan(sorted.indexOf("ike-lab-documents"));
    }

    @Test
    void topologicalSortIncludesAllSubprojects() {
        List<String> sorted = graph.topologicalSort();
        assertThat(sorted).hasSize(graph.manifest().subprojects().size());
    }

    @Test
    void topologicalSortWithSubset() {
        Set<String> subset = Set.of("komet", "tinkar-core", "ike-bom");
        List<String> sorted = graph.topologicalSort(subset);

        assertThat(sorted).containsExactlyInAnyOrder(
                "ike-bom", "tinkar-core", "komet");
        assertThat(sorted.indexOf("ike-bom"))
                .isLessThan(sorted.indexOf("tinkar-core"));
        assertThat(sorted.indexOf("tinkar-core"))
                .isLessThan(sorted.indexOf("komet"));
    }

    // ── Cascade Analysis ────────────────────────────────────────────

    @Test
    void cascadeFromTinkarCore() {
        List<String> affected = graph.cascade("tinkar-core");

        // rocks-kb, komet, komet-desktop, ike-lab-documents all depend
        // (directly or transitively) on tinkar-core
        assertThat(affected).contains(
                "rocks-kb", "komet", "komet-desktop", "ike-lab-documents");

        // ike-bom does NOT depend on tinkar-core
        assertThat(affected).doesNotContain("ike-bom", "ike-parent");
    }

    @Test
    void cascadeFromLeafSubprojectIsEmpty() {
        List<String> affected = graph.cascade("komet-desktop");
        assertThat(affected).isEmpty();
    }

    @Test
    void cascadeFromIkePipeline() {
        List<String> affected = graph.cascade("ike-pipeline");
        assertThat(affected).contains(
                "ike-lab-documents", "ike-infrastructure");
    }

    @Test
    void cascadeFromUnknownSubprojectThrows() {
        assertThatThrownBy(() -> graph.cascade("nonexistent"))
                .isInstanceOf(ManifestException.class)
                .hasMessageContaining("Unknown subproject");
    }

    // ── Cycle Detection ─────────────────────────────────────────────

    @Test
    void noCyclesInRealManifest() {
        List<String> cycle = graph.detectCycle();
        assertThat(cycle).isEmpty();
    }

    @Test
    void detectsCycle() {
        String yaml = """
                schema-version: "1.0"
                subprojects:
                  a:
                    type: software
                    depends-on:
                      - subproject: b
                        relationship: build
                  b:
                    type: software
                    depends-on:
                      - subproject: a
                        relationship: build
                """;
        Manifest m = ManifestReader.read(new StringReader(yaml));
        WorkspaceGraph g = new WorkspaceGraph(m);
        List<String> cycle = g.detectCycle();
        assertThat(cycle).isNotEmpty();
        assertThat(cycle).contains("a", "b");
    }

    // ── Verify ──────────────────────────────────────────────────────

    @Test
    void verifyRealManifestIsClean() {
        List<String> errors = graph.verify();
        assertThat(errors).isEmpty();
    }

    @Test
    void verifyDetectsMissingDependency() {
        String yaml = """
                schema-version: "1.0"
                subprojects:
                  a:
                    type: software
                    depends-on:
                      - subproject: missing
                        relationship: build
                """;
        Manifest m = ManifestReader.read(new StringReader(yaml));
        WorkspaceGraph g = new WorkspaceGraph(m);
        List<String> errors = g.verify();
        assertThat(errors).anyMatch(e -> e.contains("unknown subproject: missing"));
    }

    @Test
    void parseSilentlyIgnoresUnknownType() {
        // The subproject-type concept was removed; any `type:` field is
        // silently ignored by the reader rather than rejected.
        String yaml = """
                schema-version: "1.0"
                subprojects:
                  a:
                    type: bogus
                    repo: https://example.com/a.git
                """;
        Manifest m = ManifestReader.read(new StringReader(yaml));
        assertThat(m.subprojects()).containsOnlyKeys("a");
    }
}
