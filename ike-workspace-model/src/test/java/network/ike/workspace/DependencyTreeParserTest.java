package network.ike.workspace;

import network.ike.workspace.DependencyTreeParser.ResolvedDependency;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DependencyTreeParserTest {

    private static final String SIMPLE_TREE = """
            dev.ikm.tinkar:tinkar-core:pom:1.127.0-SNAPSHOT
            +- dev.ikm.tinkar:collection:jar:1.127.0-SNAPSHOT:compile
            |  +- org.eclipse.collections:eclipse-collections-api:jar:11.1.0:compile
            |  \\- org.eclipse.collections:eclipse-collections:jar:11.1.0:compile
            \\- dev.ikm.tinkar:common:jar:1.127.0-SNAPSHOT:compile
               +- io.activej:activej-common:jar:6.0-rc2:compile
               \\- org.slf4j:slf4j-api:jar:2.0.16:compile
            """;

    @Test
    void parsesRootArtifact() {
        List<ResolvedDependency> deps = DependencyTreeParser.parse(SIMPLE_TREE);

        assertThat(deps).isNotEmpty();
        ResolvedDependency root = deps.getFirst();
        assertThat(root.groupId()).isEqualTo("dev.ikm.tinkar");
        assertThat(root.artifactId()).isEqualTo("tinkar-core");
        assertThat(root.type()).isEqualTo("pom");
        assertThat(root.version()).isEqualTo("1.127.0-SNAPSHOT");
        assertThat(root.scope()).isEmpty();
        assertThat(root.depth()).isZero();
    }

    @Test
    void parsesDirectDependencies() {
        List<ResolvedDependency> deps = DependencyTreeParser.parse(SIMPLE_TREE);

        List<ResolvedDependency> depth1 = deps.stream()
                .filter(d -> d.depth() == 1).toList();
        assertThat(depth1).hasSize(2);
        assertThat(depth1.get(0).artifactId()).isEqualTo("collection");
        assertThat(depth1.get(1).artifactId()).isEqualTo("common");
    }

    @Test
    void parsesTransitiveDependencies() {
        List<ResolvedDependency> deps = DependencyTreeParser.parse(SIMPLE_TREE);

        List<ResolvedDependency> depth2 = deps.stream()
                .filter(d -> d.depth() == 2).toList();
        assertThat(depth2).hasSize(4);
        assertThat(depth2).extracting(ResolvedDependency::artifactId)
                .containsExactly(
                        "eclipse-collections-api",
                        "eclipse-collections",
                        "activej-common",
                        "slf4j-api");
    }

    @Test
    void parsesScope() {
        List<ResolvedDependency> deps = DependencyTreeParser.parse(SIMPLE_TREE);

        // All non-root entries have compile scope
        deps.stream().filter(d -> d.depth() > 0)
                .forEach(d -> assertThat(d.scope()).isEqualTo("compile"));
    }

    @Test
    void handlesClassifiedArtifacts() {
        String tree = """
                com.example:app:jar:1.0
                +- com.example:lib:jar:sources:1.0:compile
                \\- com.example:native:jar:linux-x64:2.0:runtime
                """;

        List<ResolvedDependency> deps = DependencyTreeParser.parse(tree);
        assertThat(deps).hasSize(3);

        // Classified: g:a:type:classifier:version:scope
        ResolvedDependency sources = deps.get(1);
        assertThat(sources.artifactId()).isEqualTo("lib");
        assertThat(sources.version()).isEqualTo("1.0");
        assertThat(sources.scope()).isEqualTo("compile");

        ResolvedDependency nat = deps.get(2);
        assertThat(nat.artifactId()).isEqualTo("native");
        assertThat(nat.version()).isEqualTo("2.0");
        assertThat(nat.scope()).isEqualTo("runtime");
    }

    @Test
    void handlesEmptyInput() {
        assertThat(DependencyTreeParser.parse("")).isEmpty();
        assertThat(DependencyTreeParser.parse("   \n\n  ")).isEmpty();
    }

    @Test
    void skipsNonCoordinateLines() {
        String withHeader = """
                [INFO] --- dependency:3.8.1:tree (default-cli) @ tinkar-core ---
                dev.ikm.tinkar:tinkar-core:pom:1.0
                +- org.slf4j:slf4j-api:jar:2.0.16:compile
                [INFO] BUILD SUCCESS
                """;

        List<ResolvedDependency> deps = DependencyTreeParser.parse(withHeader);
        assertThat(deps).hasSize(2);
        assertThat(deps.get(0).artifactId()).isEqualTo("tinkar-core");
        assertThat(deps.get(1).artifactId()).isEqualTo("slf4j-api");
    }

    @Test
    void parsesDeeplyNestedTree() {
        String deep = """
                com.example:root:jar:1.0
                \\- com.example:a:jar:1.0:compile
                   \\- com.example:b:jar:1.0:compile
                      \\- com.example:c:jar:1.0:compile
                         \\- com.example:d:jar:1.0:compile
                """;

        List<ResolvedDependency> deps = DependencyTreeParser.parse(deep);
        assertThat(deps).hasSize(5);
        assertThat(deps.get(0).depth()).isEqualTo(0);
        assertThat(deps.get(1).depth()).isEqualTo(1);
        assertThat(deps.get(2).depth()).isEqualTo(2);
        assertThat(deps.get(3).depth()).isEqualTo(3);
        assertThat(deps.get(4).depth()).isEqualTo(4);
    }
}
