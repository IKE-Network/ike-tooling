package network.ike.workspace;

import network.ike.workspace.DependencyConvergenceAnalysis.Divergence;
import network.ike.workspace.DependencyTreeParser.ResolvedDependency;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DependencyConvergenceAnalysisTest {

    @Test
    void detectsVersionDivergence() {
        Map<String, List<ResolvedDependency>> trees = new LinkedHashMap<>();

        trees.put("tinkar-core", List.of(
                new ResolvedDependency("dev.ikm.tinkar", "tinkar-core", "pom",
                        "1.0", "", 0),
                new ResolvedDependency("org.slf4j", "slf4j-api", "jar",
                        "2.0.16", "compile", 1)
        ));

        trees.put("komet", List.of(
                new ResolvedDependency("dev.ikm.komet", "komet", "pom",
                        "1.0", "", 0),
                new ResolvedDependency("org.slf4j", "slf4j-api", "jar",
                        "2.0.9", "compile", 1)
        ));

        List<Divergence> divergences =
                DependencyConvergenceAnalysis.analyze(trees);

        assertThat(divergences).hasSize(1);
        Divergence d = divergences.getFirst();
        assertThat(d.groupId()).isEqualTo("org.slf4j");
        assertThat(d.artifactId()).isEqualTo("slf4j-api");
        assertThat(d.versionCount()).isEqualTo(2);
        assertThat(d.versionToSubprojects()).containsKeys("2.0.16", "2.0.9");
    }

    @Test
    void noDivergenceWhenVersionsMatch() {
        Map<String, List<ResolvedDependency>> trees = new LinkedHashMap<>();

        trees.put("comp-a", List.of(
                dep("com.example", "root-a", "1.0", "", 0),
                dep("org.slf4j", "slf4j-api", "2.0.16", "compile", 1)
        ));
        trees.put("comp-b", List.of(
                dep("com.example", "root-b", "1.0", "", 0),
                dep("org.slf4j", "slf4j-api", "2.0.16", "compile", 1)
        ));

        assertThat(DependencyConvergenceAnalysis.analyze(trees)).isEmpty();
    }

    @Test
    void excludesRootArtifacts() {
        Map<String, List<ResolvedDependency>> trees = new LinkedHashMap<>();

        // Root artifacts naturally have different versions
        trees.put("comp-a", List.of(
                dep("com.example", "comp-a", "1.0", "", 0)
        ));
        trees.put("comp-b", List.of(
                dep("com.example", "comp-b", "2.0", "", 0)
        ));

        assertThat(DependencyConvergenceAnalysis.analyze(trees)).isEmpty();
    }

    @Test
    void excludesTestScopeDependencies() {
        Map<String, List<ResolvedDependency>> trees = new LinkedHashMap<>();

        trees.put("comp-a", List.of(
                dep("com.example", "root", "1.0", "", 0),
                dep("junit", "junit", "4.13", "test", 1)
        ));
        trees.put("comp-b", List.of(
                dep("com.example", "root", "1.0", "", 0),
                dep("junit", "junit", "5.0", "test", 1)
        ));

        assertThat(DependencyConvergenceAnalysis.analyze(trees)).isEmpty();
    }

    @Test
    void markdownReportContainsSummaryTable() {
        Map<String, List<ResolvedDependency>> trees = new LinkedHashMap<>();
        trees.put("comp-a", List.of(
                dep("x", "root", "1.0", "", 0),
                dep("org.slf4j", "slf4j-api", "2.0.16", "compile", 1)
        ));
        trees.put("comp-b", List.of(
                dep("x", "root", "1.0", "", 0),
                dep("org.slf4j", "slf4j-api", "2.0.9", "compile", 1)
        ));

        List<Divergence> divergences =
                DependencyConvergenceAnalysis.analyze(trees);
        String md = DependencyConvergenceAnalysis.formatMarkdownReport(
                divergences, "test-ws");

        assertThat(md).contains("# Dependency Convergence — test-ws");
        assertThat(md).contains("| Artifact | Versions | Subprojects |");
        assertThat(md).contains("`org.slf4j:slf4j-api`");
        assertThat(md).contains("## Details");
        assertThat(md).contains("**2.0.16**");
        assertThat(md).contains("**2.0.9**");
    }

    @Test
    void emptyReportForNoDivergences() {
        String md = DependencyConvergenceAnalysis.formatMarkdownReport(
                List.of(), "test-ws");
        assertThat(md).isEmpty();
    }

    private static ResolvedDependency dep(String groupId, String artifactId,
                                           String version, String scope,
                                           int depth) {
        return new ResolvedDependency(groupId, artifactId, "jar",
                version, scope, depth);
    }
}
