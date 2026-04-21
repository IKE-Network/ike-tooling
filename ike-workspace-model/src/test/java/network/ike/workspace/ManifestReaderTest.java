package network.ike.workspace;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManifestReaderTest {

    private static Manifest manifest;

    @BeforeAll
    static void loadManifest() {
        Path path = Path.of("src/test/resources/workspace.yaml");
        manifest = ManifestReader.read(path);
    }

    @Test
    void parsesSchemaVersion() {
        assertThat(manifest.schemaVersion()).isEqualTo("1.0");
    }

    @Test
    void parsesDefaults() {
        assertThat(manifest.defaults().branch()).isEqualTo("main");
        assertThat(manifest.defaults().mavenVersion()).isEqualTo("4.0.0-rc-5");
    }

    @Test
    void parsesAllSubprojects() {
        assertThat(manifest.subprojects()).hasSize(12);
        assertThat(manifest.subprojects()).containsKeys(
                "ike-parent", "ike-bom", "tinkar-core", "rocks-kb",
                "extra-tools", "komet", "komet-desktop",
                "ike-knowledge-source-template",
                "ike-pipeline", "ike-lab-documents", "ike-infrastructure",
                "ike");
    }

    @Test
    void parsesSubprojectFields() {
        Subproject tinkar = manifest.subprojects().get("tinkar-core");
        assertThat(tinkar.type()).isEqualTo(SubprojectType.SOFTWARE);
        assertThat(tinkar.repo()).isEqualTo("https://github.com/ikmdev/tinkar-core.git");
        assertThat(tinkar.branch()).isEqualTo("feature/kec-jan-24");
        assertThat(tinkar.version()).isEqualTo("1.127.2-kec-jan-24-SNAPSHOT");
        assertThat(tinkar.groupId()).isEqualTo("dev.ikm.tinkar");
    }

    @Test
    void parsesDependencies() {
        Subproject komet = manifest.subprojects().get("komet");
        assertThat(komet.dependsOn()).hasSize(3);
        assertThat(komet.dependsOn()).extracting(Dependency::subproject)
                .containsExactly("tinkar-core", "rocks-kb", "ike-bom");
        assertThat(komet.dependsOn()).extracting(Dependency::relationship)
                .containsOnly("build");
    }

    @Test
    void parsesContentRelationship() {
        Subproject labDocs = manifest.subprojects().get("ike-lab-documents");
        assertThat(labDocs.dependsOn()).extracting(Dependency::relationship)
                .contains("content");
        assertThat(labDocs.dependsOn()).extracting(Dependency::subproject)
                .contains("tinkar-core", "komet");
    }

    @Test
    void parsesNullVersion() {
        Subproject labDocs = manifest.subprojects().get("ike-lab-documents");
        assertThat(labDocs.version()).isNull();
    }

    @Test
    void parsesEmptyDependencies() {
        Subproject parent = manifest.subprojects().get("ike-parent");
        assertThat(parent.dependsOn()).isEmpty();
    }

    @Test
    void parsesSubprojectMavenVersionOverride() {
        Subproject komet = manifest.subprojects().get("komet");
        assertThat(komet.mavenVersion()).isEqualTo("4.0.0-rc-3");
    }

    @Test
    void inheritsNullMavenVersionWhenNotSpecified() {
        Subproject tinkar = manifest.subprojects().get("tinkar-core");
        assertThat(tinkar.mavenVersion()).isNull();
    }

    @Test
    void rejectsEmptyManifest() {
        assertThatThrownBy(() -> ManifestReader.read(new StringReader("")))
                .isInstanceOf(ManifestException.class)
                .hasMessageContaining("Empty manifest");
    }

    @Test
    void handlesMinimalManifest() {
        String yaml = """
                schema-version: "1.0"
                subprojects:
                  my-lib:
                    type: software
                    repo: https://example.com/my-lib.git
                """;
        Manifest m = ManifestReader.read(new StringReader(yaml));
        assertThat(m.subprojects()).hasSize(1);
        assertThat(m.subprojects().get("my-lib").branch()).isEqualTo("main");
        assertThat(m.defaults().mavenVersion()).isNull();
        assertThat(m.subprojects().get("my-lib").mavenVersion()).isNull();
    }

    @Test
    void returnsEmptyIdeSettingsWhenSectionAbsent() {
        assertThat(manifest.ide()).isEqualTo(IdeSettings.EMPTY);
        assertThat(manifest.ide().hasAnyValue()).isFalse();
        assertThat(manifest.ide().languageLevel()).isNull();
        assertThat(manifest.ide().jdkName()).isNull();
    }

    @Test
    void parsesIdeSettingsWhenPresent() {
        String yaml = """
                schema-version: "1.0"
                ide:
                  language-level: JDK_25_PREVIEW
                  jdk-name: "25"
                subprojects: {}
                """;
        Manifest m = ManifestReader.read(new StringReader(yaml));
        assertThat(m.ide().languageLevel()).isEqualTo("JDK_25_PREVIEW");
        assertThat(m.ide().jdkName()).isEqualTo("25");
        assertThat(m.ide().hasAnyValue()).isTrue();
    }

    @Test
    void parsesIdeSettingsWithPartialFields() {
        String yaml = """
                schema-version: "1.0"
                ide:
                  language-level: JDK_21
                subprojects: {}
                """;
        Manifest m = ManifestReader.read(new StringReader(yaml));
        assertThat(m.ide().languageLevel()).isEqualTo("JDK_21");
        assertThat(m.ide().jdkName()).isNull();
        assertThat(m.ide().hasAnyValue()).isTrue();
    }

    // ── Legacy-schema hard-cut (#150) ────────────────────────────

    @Test
    void rejectsLegacyComponentsSchema() {
        String yaml = """
                schema-version: "1.0"
                components:
                  foo:
                    type: software
                """;
        assertThatThrownBy(() -> ManifestReader.read(new StringReader(yaml)))
                .isInstanceOf(ManifestException.class)
                .hasMessageContaining("legacy 'components:' schema")
                // Issue #170: the recommended migration goal must be the
                // ACTUAL goal name. `ws:align` (without -draft / -publish)
                // does not exist; the apply variant is `ws:align-publish`.
                .hasMessageContaining("'mvn ws:align-publish'")
                .extracting(Throwable::getMessage)
                .asString()
                .doesNotMatch("(?<![-\\w])ws:align(?![-\\w])");
    }

    @Test
    void acceptsManifestWithBothKeysPreferringSubprojects() {
        // If both `subprojects:` and `components:` appear (shouldn't
        // happen in practice, but defense-in-depth), the reader uses
        // `subprojects:` rather than the legacy key.
        String yaml = """
                schema-version: "1.0"
                subprojects:
                  new-shape:
                    type: software
                components:
                  old-shape:
                    type: software
                """;
        Manifest m = ManifestReader.read(new StringReader(yaml));
        assertThat(m.subprojects()).containsOnlyKeys("new-shape");
    }
}
