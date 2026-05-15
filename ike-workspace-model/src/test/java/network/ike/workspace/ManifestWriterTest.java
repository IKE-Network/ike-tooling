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

    // ── #387: duplicate-sha bug regression tests ────────────────────────

    @Test
    void addOrUpdate_with_same_value_does_not_append_duplicate() {
        String yaml = """
                subprojects:
                  tinkar-core:
                    branch: main
                    sha: "abc123"
                    version: 1.0.0-SNAPSHOT
                """;
        // Re-applying the same SHA must NOT append a duplicate line
        // (the #387 bug: equality check fired false-negative).
        String result = ManifestWriter.addOrUpdateSubprojectField(
                yaml, "tinkar-core", "sha", "\"abc123\"", "branch");

        long shaCount = result.lines()
                .filter(l -> l.trim().startsWith("sha:"))
                .count();
        assertThat(shaCount).as("Exactly one sha line").isEqualTo(1);
        assertThat(result).contains("sha: \"abc123\"");
    }

    @Test
    void addOrUpdate_with_different_value_replaces_in_place() {
        String yaml = """
                subprojects:
                  tinkar-core:
                    branch: main
                    sha: "abc123"
                    version: 1.0.0-SNAPSHOT
                """;
        String result = ManifestWriter.addOrUpdateSubprojectField(
                yaml, "tinkar-core", "sha", "\"def456\"", "branch");

        long shaCount = result.lines()
                .filter(l -> l.trim().startsWith("sha:"))
                .count();
        assertThat(shaCount).as("Exactly one sha line").isEqualTo(1);
        assertThat(result).contains("sha: \"def456\"");
        assertThat(result).doesNotContain("abc123");
    }

    @Test
    void addOrUpdate_inserts_missing_field_after_reference() {
        String yaml = """
                subprojects:
                  tinkar-core:
                    branch: main
                    version: 1.0.0-SNAPSHOT
                """;
        String result = ManifestWriter.addOrUpdateSubprojectField(
                yaml, "tinkar-core", "sha", "\"xyz789\"", "branch");

        long shaCount = result.lines()
                .filter(l -> l.trim().startsWith("sha:"))
                .count();
        assertThat(shaCount).isEqualTo(1);
        assertThat(result).contains("sha: \"xyz789\"");
        // sha must appear after branch, before version
        int branchIdx = result.indexOf("branch: main");
        int shaIdx = result.indexOf("sha:");
        int versionIdx = result.indexOf("version:");
        assertThat(shaIdx).isGreaterThan(branchIdx);
        assertThat(shaIdx).isLessThan(versionIdx);
    }

    @Test
    void addOrUpdate_collapses_pre_existing_duplicates() {
        // Workspace already affected by the bug — three sha lines for one subproject.
        String yaml = """
                subprojects:
                  tinkar-core:
                    branch: main
                    sha: "aaa111"
                    sha: "bbb222"
                    sha: "bbb222"
                    version: 1.0.0-SNAPSHOT
                """;
        String result = ManifestWriter.addOrUpdateSubprojectField(
                yaml, "tinkar-core", "sha", "\"ccc333\"", "branch");

        long shaCount = result.lines()
                .filter(l -> l.trim().startsWith("sha:"))
                .count();
        assertThat(shaCount).as("All duplicates collapsed to one").isEqualTo(1);
        assertThat(result).contains("sha: \"ccc333\"");
        assertThat(result).doesNotContain("aaa111");
        assertThat(result).doesNotContain("bbb222");
    }

    @Test
    void addOrUpdate_leaves_other_subprojects_alone() {
        String yaml = """
                subprojects:
                  tinkar-core:
                    branch: main
                    sha: "core111"
                  komet:
                    branch: main
                    sha: "komet999"
                """;
        String result = ManifestWriter.addOrUpdateSubprojectField(
                yaml, "tinkar-core", "sha", "\"core222\"", "branch");

        assertThat(result).contains("sha: \"core222\"");
        assertThat(result).contains("sha: \"komet999\"");
        assertThat(result).doesNotContain("core111");
    }

    @Test
    void repeated_addOrUpdate_with_same_value_is_idempotent() {
        String yaml = """
                subprojects:
                  tinkar-core:
                    branch: main
                    version: 1.0.0-SNAPSHOT
                """;
        String r1 = ManifestWriter.addOrUpdateSubprojectField(
                yaml, "tinkar-core", "sha", "\"abc\"", "branch");
        String r2 = ManifestWriter.addOrUpdateSubprojectField(
                r1, "tinkar-core", "sha", "\"abc\"", "branch");
        String r3 = ManifestWriter.addOrUpdateSubprojectField(
                r2, "tinkar-core", "sha", "\"abc\"", "branch");

        assertThat(r2).isEqualTo(r3);
        assertThat(r1.lines().filter(l -> l.trim().startsWith("sha:")).count())
                .isEqualTo(1);
        assertThat(r2.lines().filter(l -> l.trim().startsWith("sha:")).count())
                .isEqualTo(1);
    }

    @Test
    void collapseDuplicates_keeps_last_occurrence_per_field() {
        String yaml = """
                subprojects:
                  tinkar-core:
                    branch: main
                    sha: "old1"
                    sha: "old2"
                    sha: "current"
                    version: 1.0.0-SNAPSHOT
                """;
        String result = ManifestWriter.collapseDuplicateSubprojectFields(yaml);

        long shaCount = result.lines()
                .filter(l -> l.trim().startsWith("sha:"))
                .count();
        assertThat(shaCount).isEqualTo(1);
        assertThat(result).contains("sha: \"current\"");
        assertThat(result).doesNotContain("old1");
        assertThat(result).doesNotContain("old2");
    }

    @Test
    void collapseDuplicates_handles_multiple_subprojects() {
        String yaml = """
                subprojects:
                  tinkar-core:
                    branch: main
                    sha: "a"
                    sha: "b"
                  komet:
                    branch: main
                    sha: "x"
                    sha: "y"
                    sha: "z"
                """;
        String result = ManifestWriter.collapseDuplicateSubprojectFields(yaml);

        long shaCount = result.lines()
                .filter(l -> l.trim().startsWith("sha:"))
                .count();
        assertThat(shaCount).isEqualTo(2);
        assertThat(result).contains("sha: \"b\"");
        assertThat(result).contains("sha: \"z\"");
    }

    @Test
    void subprojectFieldExists_true_when_field_present() {
        String yaml = """
                subprojects:
                  tinkar-core:
                    branch: main
                    sha: "abc"
                """;
        assertThat(ManifestWriter.subprojectFieldExists(yaml, "tinkar-core", "sha"))
                .isTrue();
        assertThat(ManifestWriter.subprojectFieldExists(yaml, "tinkar-core", "branch"))
                .isTrue();
    }

    @Test
    void subprojectFieldExists_false_when_field_absent() {
        String yaml = """
                subprojects:
                  tinkar-core:
                    branch: main
                """;
        assertThat(ManifestWriter.subprojectFieldExists(yaml, "tinkar-core", "sha"))
                .isFalse();
    }

    @Test
    void subprojectFieldExists_false_when_subproject_absent() {
        String yaml = """
                subprojects:
                  tinkar-core:
                    branch: main
                """;
        assertThat(ManifestWriter.subprojectFieldExists(yaml, "komet", "branch"))
                .isFalse();
    }

    @Test
    void subprojectFieldExists_is_block_bounded() {
        // Field exists in komet but not tinkar-core — must not leak across blocks.
        String yaml = """
                subprojects:
                  tinkar-core:
                    branch: main
                  komet:
                    branch: main
                    sha: "komet-only"
                """;
        assertThat(ManifestWriter.subprojectFieldExists(yaml, "tinkar-core", "sha"))
                .as("sha is in komet's block, not tinkar-core's")
                .isFalse();
        assertThat(ManifestWriter.subprojectFieldExists(yaml, "komet", "sha"))
                .isTrue();
    }

    @Test
    void collapseDuplicates_is_noop_on_clean_yaml() {
        String yaml = """
                subprojects:
                  tinkar-core:
                    branch: main
                    sha: "abc"
                    version: 1.0.0-SNAPSHOT
                  komet:
                    branch: main
                """;
        String result = ManifestWriter.collapseDuplicateSubprojectFields(yaml);
        assertThat(result).isEqualTo(yaml);
    }
}
