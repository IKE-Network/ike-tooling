package network.ike.plugin.ws;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for POM-based dependency derivation in {@link WsAddMojo}.
 *
 * <p>Verifies that {@code ws:add} correctly derives inter-component
 * dependencies from POM analysis (parent and dependency groupIds)
 * and that bidirectional resolution works regardless of the order
 * in which components are added.
 */
class WsAddDependencyDerivationTest {

    @TempDir Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        // Create a minimal workspace scaffold
        writeWorkspaceYaml("""
                schema-version: "1.0"
                generated: "2026-01-01"

                defaults:
                  branch: main

                component-types:
                  software:
                    description: Java libraries
                    build-command: "mvn clean install"
                    checkpoint-mechanism: git-tag

                components:

                groups:
                  all: []
                """);

        writePom(tempDir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.1.0">
                    <modelVersion>4.1.0</modelVersion>
                    <groupId>local.aggregate</groupId>
                    <artifactId>test-ws</artifactId>
                    <version>1.0.0-SNAPSHOT</version>
                    <packaging>pom</packaging>
                    <profiles>
                    </profiles>
                </project>
                """);
    }

    @Test
    void forward_derivation_finds_dependency_by_artifact() throws Exception {
        // Register lib-a (publishes com.example.lib:lib-a)
        createComponentDir("lib-a", "com.example.lib", null, null);
        addToManifest("lib-a", "com.example.lib");

        // Create lib-b which depends on com.example.lib:lib-a
        createComponentDir("lib-b", "com.example.app", "com.example.lib", "lib-a");

        String derivedDeps = invokeDeriveForward(
                tempDir, tempDir.resolve("workspace.yaml"),
                tempDir.resolve("lib-b"), "lib-b");

        assertThat(derivedDeps).isEqualTo("lib-a");
    }

    @Test
    void forward_derivation_returns_null_when_no_match() throws Exception {
        // Register lib-a (publishes com.example.lib:lib-a)
        createComponentDir("lib-a", "com.example.lib", null, null);
        addToManifest("lib-a", "com.example.lib");

        // Create lib-b with a dependency on com.unrelated:something — no match
        createComponentDir("lib-b", "com.example.app", "com.unrelated", "something");

        String derivedDeps = invokeDeriveForward(
                tempDir, tempDir.resolve("workspace.yaml"),
                tempDir.resolve("lib-b"), "lib-b");

        assertThat(derivedDeps).isNull();
    }

    @Test
    void forward_derivation_matches_parent_artifact() throws Exception {
        // Register parent-pom (publishes com.example.parent:parent-pom)
        createComponentDir("parent-pom", "com.example.parent", null, null);
        addToManifest("parent-pom", "com.example.parent");

        // Create child whose <parent> references com.example.parent:parent-pom
        Path childDir = tempDir.resolve("child-lib");
        Files.createDirectories(childDir);
        writePom(childDir.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>com.example.parent</groupId>
                        <artifactId>parent-pom</artifactId>
                        <version>1.0.0</version>
                    </parent>
                    <artifactId>child-lib</artifactId>
                </project>
                """);

        String derivedDeps = invokeDeriveForward(
                tempDir, tempDir.resolve("workspace.yaml"),
                childDir, "child-lib");

        assertThat(derivedDeps).isEqualTo("parent-pom");
    }

    @Test
    void forward_derivation_skips_self_with_shared_groupId() throws Exception {
        // Two components share groupId com.example.shared
        createComponentDir("comp-a", "com.example.shared", null, null);
        addToManifest("comp-a", "com.example.shared");

        // comp-b has same groupId but depends on itself (via its own artifacts)
        // — should NOT create a self-dependency
        createComponentDir("comp-b", "com.example.shared", "com.example.shared", "comp-b");
        addToManifest("comp-b", "com.example.shared");

        String derivedDeps = invokeDeriveForward(
                tempDir, tempDir.resolve("workspace.yaml"),
                tempDir.resolve("comp-b"), "comp-b");

        // Should not include comp-b itself; comp-a publishes comp-a not comp-b
        assertThat(derivedDeps).isNull();
    }

    @Test
    void backward_resolution_updates_existing_component() throws Exception {
        // Add lib-b first (depends on com.example.lib, but lib-a isn't registered yet)
        createComponentDir("lib-b", "com.example.app", "com.example.lib", "lib-a");
        addToManifest("lib-b", "com.example.app");

        // Now "add" lib-a — backfill should detect that lib-b depends on it
        createComponentDir("lib-a", "com.example.lib", null, null);

        String yaml = Files.readString(tempDir.resolve("workspace.yaml"),
                StandardCharsets.UTF_8);

        // Verify lib-b currently has no depends-on
        assertThat(yaml).contains("lib-b:");
        assertThat(yaml).doesNotContain("component: lib-a");

        // Simulate backfill: add dependency edge to lib-b
        String updated = WsAddMojo.addDependencyEdge(yaml, "lib-b", "lib-a", null);

        assertThat(updated).contains("component: lib-a");
        assertThat(updated).contains("relationship: build");
    }

    @Test
    void addDependencyEdge_converts_empty_list() {
        String yaml = """
                components:
                  my-lib:
                    type: software
                    depends-on: []
                """;

        String result = WsAddMojo.addDependencyEdge(yaml, "my-lib", "upstream", null);

        assertThat(result)
                .contains("component: upstream")
                .contains("relationship: build")
                .doesNotContain("[]");
    }

    @Test
    void addDependencyEdge_appends_to_existing_list() {
        String yaml = """
                components:
                  my-lib:
                    type: software
                    depends-on:
                      - component: existing-dep
                        relationship: build
                """;

        String result = WsAddMojo.addDependencyEdge(yaml, "my-lib", "new-dep", null);

        assertThat(result)
                .contains("component: existing-dep")
                .contains("component: new-dep");
    }

    @Test
    void deriveComponentName_strips_git_suffix() {
        assertThat(WsAddMojo.deriveComponentName(
                "https://github.com/ikmdev/tinkar-core.git"))
                .isEqualTo("tinkar-core");
    }

    @Test
    void deriveComponentName_handles_no_suffix() {
        assertThat(WsAddMojo.deriveComponentName(
                "https://github.com/ikmdev/tinkar-core"))
                .isEqualTo("tinkar-core");
    }

    @Test
    void deriveComponentName_handles_trailing_slash() {
        assertThat(WsAddMojo.deriveComponentName(
                "https://github.com/ikmdev/tinkar-core/"))
                .isEqualTo("tinkar-core");
    }

    @Test
    void addToAllGroup_adds_to_empty_group() {
        String yaml = "  all: []";
        assertThat(WsAddMojo.addToAllGroup(yaml, "new-comp"))
                .contains("all: [new-comp]");
    }

    @Test
    void addToAllGroup_appends_to_existing() {
        String yaml = "  all: [lib-a, lib-b]";
        assertThat(WsAddMojo.addToAllGroup(yaml, "app-c"))
                .contains("all: [lib-a, lib-b, app-c]");
    }

    // ── Helpers ──────────────────────────────────────────────────

    private void writeWorkspaceYaml(String content) throws Exception {
        Files.writeString(tempDir.resolve("workspace.yaml"), content,
                StandardCharsets.UTF_8);
    }

    private void writePom(Path path, String content) throws Exception {
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private void createComponentDir(String name, String groupId,
                                     String depGroupId, String depArtifact)
            throws Exception {
        Path dir = tempDir.resolve(name);
        Files.createDirectories(dir);

        StringBuilder pom = new StringBuilder();
        pom.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        pom.append("<project>\n");
        pom.append("    <modelVersion>4.0.0</modelVersion>\n");
        pom.append("    <groupId>").append(groupId).append("</groupId>\n");
        pom.append("    <artifactId>").append(name).append("</artifactId>\n");
        pom.append("    <version>1.0.0-SNAPSHOT</version>\n");

        if (depGroupId != null && depArtifact != null) {
            pom.append("    <dependencies>\n");
            pom.append("        <dependency>\n");
            pom.append("            <groupId>").append(depGroupId).append("</groupId>\n");
            pom.append("            <artifactId>").append(depArtifact).append("</artifactId>\n");
            pom.append("            <version>1.0.0</version>\n");
            pom.append("        </dependency>\n");
            pom.append("    </dependencies>\n");
        }

        pom.append("</project>\n");
        writePom(dir.resolve("pom.xml"), pom.toString());
    }

    private void addToManifest(String name, String groupId) throws Exception {
        String yaml = Files.readString(tempDir.resolve("workspace.yaml"),
                StandardCharsets.UTF_8);
        String entry = "\n  " + name + ":\n"
                + "    type: software\n"
                + "    description: " + name + "\n"
                + "    repo: https://example.com/" + name + ".git\n"
                + "    groupId: " + groupId + "\n"
                + "    depends-on: []\n";

        int groupsIdx = yaml.indexOf("\ngroups:");
        if (groupsIdx >= 0) {
            yaml = yaml.substring(0, groupsIdx) + entry + yaml.substring(groupsIdx);
        } else {
            yaml = yaml + entry;
        }
        Files.writeString(tempDir.resolve("workspace.yaml"), yaml,
                StandardCharsets.UTF_8);
    }

    /**
     * Invoke the private deriveDependencies method reflectively.
     * Converts List<DerivedDep> result to comma-separated string
     * for assertion simplicity.
     */
    @SuppressWarnings("unchecked")
    private String invokeDeriveForward(Path wsDir, Path manifestPath,
                                        Path componentDir, String componentName)
            throws Exception {
        WsAddMojo mojo = new WsAddMojo();
        var method = WsAddMojo.class.getDeclaredMethod(
                "deriveDependencies", Path.class, Path.class, Path.class, String.class);
        method.setAccessible(true);
        var result = (java.util.List<WsAddMojo.DerivedDep>) method.invoke(
                mojo, wsDir, manifestPath, componentDir, componentName);
        if (result == null || result.isEmpty()) return null;
        return result.stream()
                .map(WsAddMojo.DerivedDep::component)
                .collect(java.util.stream.Collectors.joining(","));
    }
}
