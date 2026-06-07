package network.ike.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link WorkingSetResolver} (ike-issues#609): a single repo is a
 * working set of one; a {@code workspace.yaml} resolves to its subprojects
 * plus the workspace root, found by walking up from the start directory.
 */
class WorkingSetResolverTest {

    @TempDir
    Path tempDir;

    @Test
    void singleRepo_whenNoManifestAbove() {
        WorkingSet ws = WorkingSetResolver.resolve(tempDir);

        assertThat(ws.isSingleRepo()).isTrue();
        assertThat(ws.isWorkspace()).isFalse();
        assertThat(ws.manifest()).isNull();
        assertThat(ws.members()).singleElement().satisfies(m -> {
            assertThat(m.directory()).isEqualTo(ws.root());
            assertThat(m.name()).isEqualTo(tempDir.getFileName().toString());
        });
    }

    @Test
    void workspace_membersAreSubprojectsThenRoot() throws Exception {
        writeManifest();

        WorkingSet ws = WorkingSetResolver.resolve(tempDir);

        assertThat(ws.isWorkspace()).isTrue();
        assertThat(ws.manifest()).isNotNull();
        assertThat(ws.manifest().getFileName().toString()).isEqualTo("workspace.yaml");
        assertThat(ws.members()).extracting(WorkingSet.Member::name)
                .containsExactly("lib-a", "lib-b", tempDir.getFileName().toString());
        // The workspace root is the last member.
        assertThat(ws.members().getLast().directory()).isEqualTo(ws.root());
        // A subproject member resolves under the root.
        assertThat(ws.members().getFirst().directory())
                .isEqualTo(ws.root().resolve("lib-a"));
    }

    @Test
    void resolve_searchesUpwardFromASubdirectory() throws Exception {
        writeManifest();
        Path deep = Files.createDirectories(tempDir.resolve("lib-a/src/main"));

        WorkingSet ws = WorkingSetResolver.resolve(deep);

        assertThat(ws.isWorkspace()).isTrue();
        assertThat(ws.root().getFileName()).isEqualTo(tempDir.getFileName());
    }

    private void writeManifest() throws Exception {
        Files.writeString(tempDir.resolve("workspace.yaml"), """
                schema-version: "1.0"
                defaults:
                  branch: main
                subprojects:
                  lib-a:
                    repo: https://example.com/lib-a.git
                    branch: main
                    version: "1.0.0-SNAPSHOT"
                  lib-b:
                    repo: https://example.com/lib-b.git
                    branch: main
                    version: "2.0.0-SNAPSHOT"
                """);
    }
}
