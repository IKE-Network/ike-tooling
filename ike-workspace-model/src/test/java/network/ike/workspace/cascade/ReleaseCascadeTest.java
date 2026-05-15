package network.ike.workspace.cascade;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the {@code release-cascade.yaml} model and IO
 * (IKE-Network/ike-issues#402).
 */
class ReleaseCascadeTest {

    private static final String FOUNDATION = """
            standards-version: 177
            cascade:
              - groupId: network.ike.tooling
                artifactId: ike-tooling
                url: https://github.com/IKE-Network/ike-tooling.git
              - groupId: network.ike.docs
                artifactId: ike-docs
                url: https://github.com/IKE-Network/ike-docs.git
                consumes:
                  - network.ike.tooling
              - groupId: network.ike.platform
                artifactId: ike-platform
                url: https://github.com/IKE-Network/ike-platform.git
                consumes:
                  - network.ike.tooling
                  - network.ike.docs
            """;

    @Test
    void parses_the_foundation_manifest() {
        ReleaseCascade cascade = ReleaseCascadeIo.read(
                new StringReader(FOUNDATION));

        assertThat(cascade.standardsVersion()).isEqualTo("177");
        assertThat(cascade.repos()).hasSize(3);
        assertThat(cascade.repos().stream().map(CascadeRepo::artifactId))
                .containsExactly("ike-tooling", "ike-docs", "ike-platform");
        // repo defaults to artifactId when omitted.
        assertThat(cascade.repos().stream().map(CascadeRepo::repo))
                .containsExactly("ike-tooling", "ike-docs", "ike-platform");
        assertThat(cascade.find("network.ike.docs")).get()
                .extracting(CascadeRepo::ga)
                .isEqualTo("network.ike.docs:ike-docs");
        assertThat(cascade.find("network.ike.tooling")).get()
                .extracting(CascadeRepo::url)
                .isEqualTo("https://github.com/IKE-Network/ike-tooling.git");
    }

    @Test
    void repo_field_overrides_artifactId_default() {
        ReleaseCascade cascade = ReleaseCascadeIo.read(new StringReader("""
                cascade:
                  - groupId: network.ike.tooling
                    artifactId: ike-tooling
                    repo: ike-tooling-checkout
                """));
        assertThat(cascade.repos().get(0).repo())
                .isEqualTo("ike-tooling-checkout");
    }

    @Test
    void url_is_optional() {
        ReleaseCascade cascade = ReleaseCascadeIo.read(new StringReader("""
                cascade:
                  - groupId: network.ike.tooling
                    artifactId: ike-tooling
                """));
        assertThat(cascade.repos().get(0).url()).isNull();
    }

    @Test
    void downstreamOf_returns_transitive_consumers_in_order() {
        ReleaseCascade cascade = ReleaseCascadeIo.read(
                new StringReader(FOUNDATION));

        assertThat(cascade.downstreamOf("network.ike.tooling")
                        .stream().map(CascadeRepo::artifactId))
                .containsExactly("ike-docs", "ike-platform");
        assertThat(cascade.downstreamOf("network.ike.docs")
                        .stream().map(CascadeRepo::artifactId))
                .containsExactly("ike-platform");
        assertThat(cascade.downstreamOf("network.ike.platform")).isEmpty();
    }

    @Test
    void downstreamOf_unknown_groupId_is_empty() {
        ReleaseCascade cascade = ReleaseCascadeIo.read(
                new StringReader(FOUNDATION));

        assertThat(cascade.downstreamOf("dev.ikm.komet")).isEmpty();
        assertThat(cascade.contains("dev.ikm.komet")).isFalse();
    }

    @Test
    void malformed_manifest_is_rejected() {
        assertThatThrownBy(() -> ReleaseCascadeIo.read(
                new StringReader("standards-version: 1\n")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cascade");
        assertThatThrownBy(() -> ReleaseCascadeIo.read(
                new StringReader("cascade:\n  - artifactId: x\n")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("groupId");
        assertThatThrownBy(() -> ReleaseCascadeIo.read(
                new StringReader("cascade:\n  - groupId: x\n")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("artifactId");
    }

    @Test
    void load_reads_a_manifest_at_the_given_path(@TempDir Path tmp)
            throws IOException {
        Path manifest = tmp.resolve("release-cascade.yaml");
        Files.writeString(manifest, FOUNDATION, StandardCharsets.UTF_8);

        Optional<ReleaseCascade> loaded = ReleaseCascadeIo.load(manifest);
        assertThat(loaded).get()
                .extracting(c -> c.repos().size()).isEqualTo(3);
    }

    @Test
    void readFromZip_reads_manifest_from_cascade_artifact(
            @TempDir Path tmp) throws IOException {
        Path zip = tmp.resolve("ike-build-standards-177-cascade.zip");
        try (ZipOutputStream zos = new ZipOutputStream(
                Files.newOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry("release-cascade.yaml"));
            zos.write(FOUNDATION.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        ReleaseCascade cascade = ReleaseCascadeIo.readFromZip(zip);
        assertThat(cascade.repos()).hasSize(3);
        assertThat(cascade.find("network.ike.docs")).isPresent();
    }

    @Test
    void readFromZip_rejects_a_zip_without_the_manifest(@TempDir Path tmp)
            throws IOException {
        Path zip = tmp.resolve("no-manifest.zip");
        try (ZipOutputStream zos = new ZipOutputStream(
                Files.newOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry("other.txt"));
            zos.write("x".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        assertThatThrownBy(() -> ReleaseCascadeIo.readFromZip(zip))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("release-cascade.yaml");
    }

    @Test
    void load_degrades_gracefully_when_absent(@TempDir Path tmp) {
        assertThat(ReleaseCascadeIo.load(
                tmp.resolve("release-cascade.yaml"))).isEmpty();
        assertThat(ReleaseCascadeIo.load(null)).isEmpty();
    }

    @Test
    void reporter_self_resolves_by_coordinates_then_groupId() {
        ReleaseCascade cascade = ReleaseCascadeIo.read(
                new StringReader(FOUNDATION));

        // Exact GA match.
        assertThat(CascadeReporter.self(cascade,
                        "network.ike.docs", "ike-docs"))
                .get().extracting(CascadeRepo::artifactId)
                .isEqualTo("ike-docs");
        // groupId-only fallback (artifactId is a submodule, not the
        // reactor root, but the groupId still resolves uniquely).
        assertThat(CascadeReporter.self(cascade,
                        "network.ike.tooling", "ike-maven-plugin"))
                .get().extracting(CascadeRepo::artifactId)
                .isEqualTo("ike-tooling");
        // Ordinary consumer — not a cascade member.
        assertThat(CascadeReporter.self(cascade,
                        "dev.ikm.komet", "komet-desktop")).isEmpty();
    }

    @Test
    void reporter_publish_footer_names_downstream_and_next_step() {
        ReleaseCascade cascade = ReleaseCascadeIo.read(
                new StringReader(FOUNDATION));
        CascadeRepo self = cascade.find("network.ike.tooling").orElseThrow();

        List<String> footer = CascadeReporter.publishFooter(cascade, self);

        assertThat(footer).anyMatch(l -> l.contains("ike-tooling released"));
        assertThat(footer).anyMatch(l -> l.contains("cd ../ike-docs"));
        assertThat(footer).anyMatch(
                l -> l.contains("ws:cascade-foundation-publish"));
    }

    @Test
    void reporter_publish_footer_marks_end_of_cascade() {
        ReleaseCascade cascade = ReleaseCascadeIo.read(
                new StringReader(FOUNDATION));
        CascadeRepo self = cascade.find("network.ike.platform").orElseThrow();

        List<String> footer = CascadeReporter.publishFooter(cascade, self);

        assertThat(footer).anyMatch(l -> l.contains("End of the foundation"));
    }
}
