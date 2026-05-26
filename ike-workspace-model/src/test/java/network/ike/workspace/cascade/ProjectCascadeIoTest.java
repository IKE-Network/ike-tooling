package network.ike.workspace.cascade;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the per-project {@code release-cascade.yaml} model and IO
 * (IKE-Network/ike-issues#420).
 */
class ProjectCascadeIoTest {

    private static final String HEAD = """
            schema: 1
            head: true
            downstream:
              - groupId: network.ike.docs
                artifactId: ike-docs
                url: https://github.com/IKE-Network/ike-docs.git
            """;

    private static final String MIDDLE = """
            schema: 1
            upstream:
              - groupId: network.ike.tooling
                artifactId: ike-tooling
                version-property: ike-tooling.version
                url: https://github.com/IKE-Network/ike-tooling.git
            downstream:
              - groupId: network.ike.platform
                artifactId: ike-platform
                repo: ike-platform-checkout
                url: https://github.com/IKE-Network/ike-platform.git
            """;

    private static final String TERMINAL = """
            schema: 1
            terminal: true
            upstream:
              - groupId: network.ike.tooling
                artifactId: ike-tooling
                version-property: ike-tooling.version
                url: https://github.com/IKE-Network/ike-tooling.git
              - groupId: network.ike.docs
                artifactId: ike-docs
                version-property: ike-docs.version
                url: https://github.com/IKE-Network/ike-docs.git
            """;

    @Test
    void parses_a_head_manifest() {
        ProjectCascade cascade = ProjectCascadeIo.read(
                new StringReader(HEAD));

        assertThat(cascade.head()).isTrue();
        assertThat(cascade.terminal()).isFalse();
        assertThat(cascade.upstream()).isEmpty();
        assertThat(cascade.downstream()).singleElement()
                .extracting(CascadeEdge::ga)
                .isEqualTo("network.ike.docs:ike-docs");
        // repo defaults to artifactId.
        assertThat(cascade.downstream().get(0).repo()).isEqualTo("ike-docs");
    }

    @Test
    void parses_a_middle_manifest() {
        ProjectCascade cascade = ProjectCascadeIo.read(
                new StringReader(MIDDLE));

        assertThat(cascade.head()).isFalse();
        assertThat(cascade.terminal()).isFalse();
        assertThat(cascade.upstream()).singleElement()
                .satisfies(e -> {
                    assertThat(e.ga())
                            .isEqualTo("network.ike.tooling:ike-tooling");
                    // versionProperty() is derived from the coordinate
                    // using the typed-marker family (#525) — not read
                    // from the YAML. See IKE-Network/ike-issues#496.
                    // The legacy "ike-tooling.version" field in the
                    // YAML manifest is read and discarded.
                    assertThat(e.versionProperty())
                            .isEqualTo("network.ike.tooling__GA__ike-tooling__VERSION");
                    assertThat(e.versionPropertyLegacy())
                            .isEqualTo("network.ike.tooling·ike-tooling");
                });
        // repo overrides the artifactId default.
        assertThat(cascade.downstream().get(0).repo())
                .isEqualTo("ike-platform-checkout");
    }

    @Test
    void parses_a_terminal_manifest() {
        ProjectCascade cascade = ProjectCascadeIo.read(
                new StringReader(TERMINAL));

        assertThat(cascade.terminal()).isTrue();
        assertThat(cascade.downstream()).isEmpty();
        assertThat(cascade.upstream()).extracting(CascadeEdge::ga)
                .containsExactly("network.ike.tooling:ike-tooling",
                        "network.ike.docs:ike-docs");
    }

    @Test
    void head_marker_must_agree_with_the_upstream_edges() {
        // Has an upstream edge but omits 'head' — fine. Declares
        // 'head: true' while listing an upstream edge — error.
        assertThatThrownBy(() -> ProjectCascadeIo.read(new StringReader("""
                head: true
                terminal: true
                upstream:
                  - groupId: network.ike.tooling
                    artifactId: ike-tooling
                    version-property: ike-tooling.version
                """)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("head");
    }

    @Test
    void missing_head_marker_on_a_root_manifest_is_rejected() {
        // No upstream edges, but 'head: true' is not declared.
        assertThatThrownBy(() -> ProjectCascadeIo.read(new StringReader("""
                terminal: true
                """)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("head");
    }

    @Test
    void terminal_marker_must_agree_with_the_downstream_edges() {
        assertThatThrownBy(() -> ProjectCascadeIo.read(new StringReader("""
                head: true
                terminal: true
                downstream:
                  - groupId: network.ike.docs
                    artifactId: ike-docs
                """)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("terminal");
    }

    @Test
    void missing_terminal_marker_on_a_leaf_manifest_is_rejected() {
        assertThatThrownBy(() -> ProjectCascadeIo.read(new StringReader("""
                head: true
                """)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("terminal");
    }

    @Test
    void edge_without_coordinates_is_rejected() {
        assertThatThrownBy(() -> ProjectCascadeIo.read(new StringReader("""
                head: true
                downstream:
                  - artifactId: ike-docs
                """)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("groupId");
    }

    @Test
    void load_reads_a_manifest_at_the_given_path(@TempDir Path tmp)
            throws IOException {
        Path manifest = tmp.resolve("release-cascade.yaml");
        Files.writeString(manifest, TERMINAL, StandardCharsets.UTF_8);

        assertThat(ProjectCascadeIo.load(manifest)).get()
                .extracting(ProjectCascade::terminal).isEqualTo(true);
    }

    @Test
    void load_degrades_gracefully_when_absent(@TempDir Path tmp) {
        assertThat(ProjectCascadeIo.load(
                tmp.resolve("release-cascade.yaml"))).isEmpty();
        assertThat(ProjectCascadeIo.load(null)).isEmpty();
    }
}
