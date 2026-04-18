package network.ike.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ManifestWriterTest {

    @Test
    void updateSingleBranch() {
        String yaml = """
                subprojects:
                  tinkar-core:
                    type: software
                    branch: main
                    version: 1.0.0-SNAPSHOT
                  komet:
                    type: software
                    branch: main
                """;
        String result = ManifestWriter.updateSubprojectBranch(yaml, "tinkar-core", "feature/shield");
        assertThat(result).contains("tinkar-core:");
        assertThat(result).contains("branch: feature/shield");
        // komet should be unchanged
        assertThat(result).matches("(?s).*komet:.*branch: main.*");
    }

    @Test
    void updateMultipleBranches(@TempDir Path tmp) throws IOException {
        String yaml = """
                subprojects:
                  tinkar-core:
                    type: software
                    branch: main
                    version: 1.0.0-SNAPSHOT
                  komet:
                    type: software
                    branch: main
                    version: 2.0.0-SNAPSHOT
                """;
        Path manifest = tmp.resolve("workspace.yaml");
        Files.writeString(manifest, yaml);

        Map<String, String> updates = new LinkedHashMap<>();
        updates.put("tinkar-core", "feature/march");
        updates.put("komet", "feature/march");
        ManifestWriter.updateBranches(manifest, updates);

        String result = Files.readString(manifest);
        assertThat(result).contains("branch: feature/march");
        assertThat(result).doesNotContain("branch: main");
    }

    @Test
    void preservesComments(@TempDir Path tmp) throws IOException {
        String yaml = """
                # This is a comment
                subprojects:
                  # Core subproject
                  tinkar-core:
                    type: software
                    description: The core library
                    branch: main
                    version: 1.0.0-SNAPSHOT
                """;
        Path manifest = tmp.resolve("workspace.yaml");
        Files.writeString(manifest, yaml);

        ManifestWriter.updateBranches(manifest, Map.of("tinkar-core", "feature/test"));

        String result = Files.readString(manifest);
        assertThat(result).contains("# This is a comment");
        assertThat(result).contains("# Core subproject");
        assertThat(result).contains("branch: feature/test");
    }

    @Test
    void unknownSubprojectIsNoOp() {
        String yaml = """
                subprojects:
                  tinkar-core:
                    branch: main
                """;
        String result = ManifestWriter.updateSubprojectBranch(yaml, "nonexistent", "feature/x");
        assertThat(result).isEqualTo(yaml);
    }

    @Test
    void insertsBranchFieldWhenSubprojectHasNone(@TempDir Path tmp) throws IOException {
        // Issue #159: subprojects that inherit branch from defaults have
        // no per-subproject branch: field. Earlier versions of
        // updateBranches silently no-oped on those, leaving git on
        // feature/X but the manifest claiming main.
        String yaml = """
                subprojects:
                  tinkar-core:
                    type: software
                    repo: https://example.com/tinkar-core.git
                    version: 1.0.0-SNAPSHOT
                  komet:
                    type: software
                    repo: https://example.com/komet.git
                    version: 2.0.0-SNAPSHOT
                """;
        Path manifest = tmp.resolve("workspace.yaml");
        Files.writeString(manifest, yaml);

        ManifestWriter.updateBranches(manifest, Map.of(
                "tinkar-core", "feature/march",
                "komet", "feature/march"));

        String result = Files.readString(manifest);
        assertThat(result).contains("    type: software");
        assertThat(result).contains("    branch: feature/march");
        // Both subprojects got the branch field inserted
        long branchLineCount = result.lines()
                .filter(l -> l.trim().equals("branch: feature/march"))
                .count();
        assertThat(branchLineCount).isEqualTo(2);
    }
}
