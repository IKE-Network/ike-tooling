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
    void parsesAllComponents() {
        assertThat(manifest.components()).hasSize(12);
        assertThat(manifest.components()).containsKeys(
                "ike-parent", "ike-bom", "tinkar-core", "rocks-kb",
                "extra-tools", "komet", "komet-desktop",
                "ike-knowledge-source-template",
                "ike-pipeline", "ike-lab-documents", "ike-infrastructure",
                "ike");
    }

    @Test
    void parsesComponentFields() {
        Component tinkar = manifest.components().get("tinkar-core");
        assertThat(tinkar.type()).isEqualTo(SubprojectType.SOFTWARE);
        assertThat(tinkar.repo()).isEqualTo("https://github.com/ikmdev/tinkar-core.git");
        assertThat(tinkar.branch()).isEqualTo("feature/kec-jan-24");
        assertThat(tinkar.version()).isEqualTo("1.127.2-kec-jan-24-SNAPSHOT");
        assertThat(tinkar.groupId()).isEqualTo("dev.ikm.tinkar");
    }

    @Test
    void parsesDependencies() {
        Component komet = manifest.components().get("komet");
        assertThat(komet.dependsOn()).hasSize(3);
        assertThat(komet.dependsOn()).extracting(Dependency::component)
                .containsExactly("tinkar-core", "rocks-kb", "ike-bom");
        assertThat(komet.dependsOn()).extracting(Dependency::relationship)
                .containsOnly("build");
    }

    @Test
    void parsesContentRelationship() {
        Component labDocs = manifest.components().get("ike-lab-documents");
        assertThat(labDocs.dependsOn()).extracting(Dependency::relationship)
                .contains("content");
        assertThat(labDocs.dependsOn()).extracting(Dependency::component)
                .contains("tinkar-core", "komet");
    }

    @Test
    void parsesNullVersion() {
        Component labDocs = manifest.components().get("ike-lab-documents");
        assertThat(labDocs.version()).isNull();
    }

    @Test
    void parsesEmptyDependencies() {
        Component parent = manifest.components().get("ike-parent");
        assertThat(parent.dependsOn()).isEmpty();
    }

    @Test
    void parsesComponentMavenVersionOverride() {
        Component komet = manifest.components().get("komet");
        assertThat(komet.mavenVersion()).isEqualTo("4.0.0-rc-3");
    }

    @Test
    void inheritsNullMavenVersionWhenNotSpecified() {
        Component tinkar = manifest.components().get("tinkar-core");
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
                components:
                  my-lib:
                    type: software
                    repo: https://example.com/my-lib.git
                """;
        Manifest m = ManifestReader.read(new StringReader(yaml));
        assertThat(m.components()).hasSize(1);
        assertThat(m.components().get("my-lib").branch()).isEqualTo("main");
        assertThat(m.defaults().mavenVersion()).isNull();
        assertThat(m.components().get("my-lib").mavenVersion()).isNull();
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
                components: {}
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
                components: {}
                """;
        Manifest m = ManifestReader.read(new StringReader(yaml));
        assertThat(m.ide().languageLevel()).isEqualTo("JDK_21");
        assertThat(m.ide().jdkName()).isNull();
        assertThat(m.ide().hasAnyValue()).isTrue();
    }
}
