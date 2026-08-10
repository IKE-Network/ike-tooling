package network.ike.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReleaseRecordFileTest {

    private static ReleaseRecord.MemberRelease row(String version) {
        return new ReleaseRecord.MemberRelease(
                version, "v" + version, "abc123def456", "2026-08-10");
    }

    @Test
    void pathFor_builds_canonical_releases_path(@TempDir Path tmp) {
        Path path = ReleaseRecordFile.pathFor(tmp, "komet-wsr-1");
        assertThat(path).isEqualTo(
                tmp.resolve("releases").resolve("release-komet-wsr-1.yaml"));
    }

    @Test
    void pathFor_rejects_blank_cycle(@TempDir Path tmp) {
        assertThatThrownBy(() -> ReleaseRecordFile.pathFor(tmp, " "))
                .isInstanceOf(ManifestException.class)
                .hasMessageContaining("cycle");
    }

    @Test
    void write_then_read_roundtrips_all_fields(@TempDir Path tmp)
            throws IOException {
        ReleaseRecord record = ReleaseRecord.start("komet-wsr-1", "2026-08-10")
                .withMember("komet-bom", row("3.0.7"))
                .withMember("tinkar-schema", row("1.43.0"));
        Path path = ReleaseRecordFile.pathFor(tmp, "komet-wsr-1");

        ReleaseRecordFile.write(path, record);
        ReleaseRecord loaded = ReleaseRecordFile.read(path);

        assertThat(loaded.cycle()).isEqualTo("komet-wsr-1");
        assertThat(loaded.started()).isEqualTo("2026-08-10");
        assertThat(loaded.members()).containsOnlyKeys(
                "komet-bom", "tinkar-schema");
        ReleaseRecord.MemberRelease bom = loaded.members().get("komet-bom");
        assertThat(bom.version()).isEqualTo("3.0.7");
        assertThat(bom.tag()).isEqualTo("v3.0.7");
        assertThat(bom.sha()).isEqualTo("abc123def456");
        assertThat(bom.recorded()).isEqualTo("2026-08-10");
    }

    @Test
    void members_read_back_in_release_order(@TempDir Path tmp)
            throws IOException {
        // Deliberately non-alphabetical: release order must win.
        ReleaseRecord record = ReleaseRecord.start("c1", null)
                .withMember("zeta", row("2.0.0"))
                .withMember("alpha", row("3.0.0"))
                .withMember("mid", row("1.0.0"));
        Path path = ReleaseRecordFile.pathFor(tmp, "c1");
        ReleaseRecordFile.write(path, record);

        assertThat(ReleaseRecordFile.read(path).members().keySet())
                .containsExactly("zeta", "alpha", "mid");
    }

    @Test
    void withMember_replaces_existing_row_keeping_position() {
        ReleaseRecord record = ReleaseRecord.start("c1", null)
                .withMember("komet-bom", row("3.0.7"))
                .withMember("tinkar-schema", row("1.43.0"))
                .withMember("komet-bom", row("3.0.8"));
        assertThat(record.members().keySet())
                .containsExactly("komet-bom", "tinkar-schema");
        assertThat(record.members().get("komet-bom").version())
                .isEqualTo("3.0.8");
    }

    @Test
    void empty_cycle_renders_and_reads_back_empty(@TempDir Path tmp)
            throws IOException {
        Path path = ReleaseRecordFile.pathFor(tmp, "empty-cycle");
        ReleaseRecordFile.write(path, ReleaseRecord.start("empty-cycle", null));

        assertThat(Files.readString(path)).contains("members: {}");
        ReleaseRecord loaded = ReleaseRecordFile.read(path);
        assertThat(loaded.members()).isEmpty();
        assertThat(loaded.started()).isNull();
    }

    @Test
    void rendered_form_is_byte_stable(@TempDir Path tmp) throws IOException {
        ReleaseRecord record = ReleaseRecord.start("c1", "2026-08-10")
                .withMember("komet-bom", row("3.0.7"));
        Path path = ReleaseRecordFile.pathFor(tmp, "c1");

        ReleaseRecordFile.write(path, record);
        String first = Files.readString(path);
        ReleaseRecordFile.write(path, ReleaseRecordFile.read(path));
        String second = Files.readString(path);

        assertThat(second).isEqualTo(first);
    }

    @Test
    void quoted_dates_survive_as_strings(@TempDir Path tmp)
            throws IOException {
        Path path = ReleaseRecordFile.pathFor(tmp, "c1");
        ReleaseRecordFile.write(path,
                ReleaseRecord.start("c1", "2026-08-10")
                        .withMember("komet-bom", row("3.0.7")));
        // The rendered scalars are quoted, so YAML's date typing never
        // engages on read-back.
        assertThat(Files.readString(path)).contains("started: \"2026-08-10\"");
        assertThat(ReleaseRecordFile.read(path).started())
                .isEqualTo("2026-08-10");
    }

    @Test
    void unquoted_scalars_from_foreign_writers_normalize_to_strings(
            @TempDir Path tmp) throws IOException {
        Path path = tmp.resolve("release-foreign.yaml");
        Files.writeString(path, """
                cycle: c1
                members:
                  komet-bom:
                    version: 150
                    tag: v150
                    sha: 22231e64
                """);
        ReleaseRecord loaded = ReleaseRecordFile.read(path);
        assertThat(loaded.members().get("komet-bom").version())
                .isEqualTo("150");
    }

    @Test
    void read_rejects_member_row_missing_required_field(@TempDir Path tmp)
            throws IOException {
        Path path = tmp.resolve("release-bad.yaml");
        Files.writeString(path, """
                cycle: c1
                members:
                  komet-bom:
                    version: "3.0.7"
                    tag: v3.0.7
                """);
        assertThatThrownBy(() -> ReleaseRecordFile.read(path))
                .isInstanceOf(ManifestException.class)
                .hasMessageContaining("sha");
    }

    @Test
    void read_rejects_non_map_document(@TempDir Path tmp) throws IOException {
        Path path = tmp.resolve("release-scalar.yaml");
        Files.writeString(path, "just a scalar\n");
        assertThatThrownBy(() -> ReleaseRecordFile.read(path))
                .isInstanceOf(ManifestException.class)
                .hasMessageContaining("not a YAML map");
    }

    @Test
    void record_validation_rejects_blank_cycle_and_null_members() {
        assertThatThrownBy(() -> ReleaseRecord.start(" ", null))
                .isInstanceOf(ManifestException.class)
                .hasMessageContaining("cycle");
        assertThatThrownBy(() -> new ReleaseRecord("c1", null, null))
                .isInstanceOf(ManifestException.class)
                .hasMessageContaining("members");
    }

    @Test
    void member_release_validation_requires_version_tag_sha() {
        assertThatThrownBy(() -> new ReleaseRecord.MemberRelease(
                null, "v1", "abc", null))
                .isInstanceOf(ManifestException.class)
                .hasMessageContaining("version");
        assertThatThrownBy(() -> new ReleaseRecord.MemberRelease(
                "1", " ", "abc", null))
                .isInstanceOf(ManifestException.class)
                .hasMessageContaining("tag");
        assertThatThrownBy(() -> new ReleaseRecord.MemberRelease(
                "1", "v1", "", null))
                .isInstanceOf(ManifestException.class)
                .hasMessageContaining("sha");
    }
}
