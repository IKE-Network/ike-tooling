package network.ike.workspace;

import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the schema 1.1 additions: typed {@code workspace-root}
 * coordinates (ike-issues#183) and per-subproject alignment fields
 * {@code state} / {@code tag} / {@code kind} (ike-issues#233).
 *
 * <p>Backward-compat is exercised explicitly: legacy 1.0 manifests
 * with neither block present must still parse cleanly, with sensible
 * defaults ({@code workspaceRoot=null},
 * {@code state="snapshot"}).
 */
class Schema11Test {

    // ── workspace-root parsing (#183) ────────────────────────────

    @Test
    void parses_workspace_root_when_present() {
        String yaml = """
                schema-version: "1.1"
                generated: "2026-05-05"

                workspace-root:
                  groupId: network.ike.workspace
                  artifactId: my-ws
                  version: 3-SNAPSHOT

                defaults:
                  branch: main

                subprojects:
                """;
        Manifest m = ManifestReader.read(new StringReader(yaml));
        assertThat(m.workspaceRoot()).isNotNull();
        assertThat(m.workspaceRoot().groupId()).isEqualTo("network.ike.workspace");
        assertThat(m.workspaceRoot().artifactId()).isEqualTo("my-ws");
        assertThat(m.workspaceRoot().version()).isEqualTo("3-SNAPSHOT");
    }

    @Test
    void workspace_root_null_when_absent_legacy_compat() {
        String yaml = """
                schema-version: "1.0"

                defaults:
                  branch: main

                subprojects:
                """;
        Manifest m = ManifestReader.read(new StringReader(yaml));
        assertThat(m.workspaceRoot())
                .as("legacy 1.0 manifests must read with workspaceRoot=null")
                .isNull();
    }

    // ── state / tag / kind parsing (#233) ────────────────────────

    @Test
    void state_defaults_to_snapshot_when_absent() {
        String yaml = """
                schema-version: "1.0"
                defaults:
                  branch: main
                subprojects:
                  lib-a:
                    repo: https://github.com/example/lib-a.git
                """;
        Manifest m = ManifestReader.read(new StringReader(yaml));
        Subproject sub = m.subprojects().get("lib-a");
        assertThat(sub.state()).isEqualTo(Subproject.STATE_SNAPSHOT);
        assertThat(sub.isSnapshotAligned()).isTrue();
        assertThat(sub.isTagAligned()).isFalse();
        assertThat(sub.tag()).isNull();
        assertThat(sub.kind()).isNull();
    }

    @Test
    void parses_explicit_snapshot_state() {
        String yaml = """
                schema-version: "1.1"
                defaults:
                  branch: main
                subprojects:
                  lib-a:
                    repo: https://example.com/x.git
                    state: snapshot
                """;
        Subproject sub = ManifestReader.read(new StringReader(yaml))
                .subprojects().get("lib-a");
        assertThat(sub.isSnapshotAligned()).isTrue();
        assertThat(sub.tag()).isNull();
        assertThat(sub.kind()).isNull();
    }

    @Test
    void parses_tag_aligned_release() {
        String yaml = """
                schema-version: "1.1"
                defaults:
                  branch: main
                subprojects:
                  ike-docs:
                    repo: https://github.com/ikmdev/ike-docs.git
                    state: tag-aligned
                    tag: ike-docs-3
                    kind: release
                    version: "3"
                """;
        Subproject sub = ManifestReader.read(new StringReader(yaml))
                .subprojects().get("ike-docs");
        assertThat(sub.state()).isEqualTo(Subproject.STATE_TAG_ALIGNED);
        assertThat(sub.tag()).isEqualTo("ike-docs-3");
        assertThat(sub.kind()).isEqualTo(Subproject.KIND_RELEASE);
        assertThat(sub.isSnapshotAligned()).isFalse();
        assertThat(sub.isTagAligned()).isTrue();
    }

    @Test
    void parses_tag_aligned_checkpoint() {
        String yaml = """
                schema-version: "1.1"
                defaults:
                  branch: main
                subprojects:
                  ike-platform:
                    repo: https://github.com/ikmdev/ike-platform.git
                    state: tag-aligned
                    tag: checkpoint-2026-04-22
                    kind: checkpoint
                    version: "14-checkpoint"
                """;
        Subproject sub = ManifestReader.read(new StringReader(yaml))
                .subprojects().get("ike-platform");
        assertThat(sub.kind()).isEqualTo(Subproject.KIND_CHECKPOINT);
        assertThat(sub.tag()).isEqualTo("checkpoint-2026-04-22");
    }

    // ── Sentinel constants (compiler-visibility) ────────────────

    @Test
    void state_constants_match_yaml_string_values() {
        // If these drift, every consumer using the constant will
        // silently disagree with hand-edited yaml. The compiler can't
        // catch the drift, but this test will (per
        // feedback_compiler_visibility).
        assertThat(Subproject.STATE_SNAPSHOT).isEqualTo("snapshot");
        assertThat(Subproject.STATE_TAG_ALIGNED).isEqualTo("tag-aligned");
        assertThat(Subproject.KIND_RELEASE).isEqualTo("release");
        assertThat(Subproject.KIND_CHECKPOINT).isEqualTo("checkpoint");
    }
}
