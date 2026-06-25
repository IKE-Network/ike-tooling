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
        assertThat(ws.baseName()).isEqualTo(tempDir.getFileName().toString());
        assertThat(ws.members()).singleElement().satisfies(m -> {
            assertThat(m.directory()).isEqualTo(ws.root());
            assertThat(m.name()).isEqualTo(tempDir.getFileName().toString());
            // The lone member is the root of its own trivial set.
            assertThat(m.kind()).isEqualTo(WorkingSet.Member.Kind.AGGREGATOR);
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
        // Subprojects first, then the aggregator (workspace root).
        assertThat(ws.members()).extracting(WorkingSet.Member::kind)
                .containsExactly(WorkingSet.Member.Kind.SUBPROJECT,
                        WorkingSet.Member.Kind.SUBPROJECT,
                        WorkingSet.Member.Kind.AGGREGATOR);
        // A 1.0 manifest has no workspace-root: base name falls back to the dir.
        assertThat(ws.baseName()).isEqualTo(tempDir.getFileName().toString());
        // The workspace root is the last member.
        assertThat(ws.members().getLast().directory()).isEqualTo(ws.root());
        assertThat(ws.members().getLast().isAggregator()).isTrue();
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

    @Test
    void workspace_baseNameFromWorkspaceRootArtifactId() throws Exception {
        Files.writeString(tempDir.resolve("workspace.yaml"), """
                schema-version: "1.1"
                workspace-root:
                  groupId: network.ike.komet
                  artifactId: ike-komet-wsr
                  version: "1-SNAPSHOT"
                defaults:
                  branch: main
                subprojects:
                  lib-a:
                    repo: https://example.com/lib-a.git
                    branch: main
                """);

        WorkingSet ws = WorkingSetResolver.resolve(tempDir);

        // Base name comes from workspace-root:artifactId, not the temp dir name.
        assertThat(ws.baseName()).isEqualTo("ike-komet-wsr");
        assertThat(ws.members()).extracting(WorkingSet.Member::name)
                .containsExactly("lib-a", tempDir.getFileName().toString());
        assertThat(ws.members().getLast().kind())
                .isEqualTo(WorkingSet.Member.Kind.AGGREGATOR);
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
