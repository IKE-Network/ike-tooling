package network.ike.plugin.scaffold;

import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScaffoldManifestIoTest {

    @Test
    void parsesMinimalManifest() {
        String yaml = """
                schema: 1
                standards-version: "7"
                files:
                  - dest: mvnw
                    scope: project
                    tier: tool-owned
                    source: mvnw
                """;
        ScaffoldManifest m = ScaffoldManifestIo.read(
                new StringReader(yaml));
        assertThat(m.schema()).isEqualTo(1);
        assertThat(m.standardsVersion()).isEqualTo("7");
        assertThat(m.entries()).hasSize(1);

        ManifestEntry e = m.entries().get(0);
        assertThat(e.dest()).isEqualTo("mvnw");
        assertThat(e.scope()).isEqualTo(ScaffoldScope.PROJECT);
        assertThat(e.tier()).isEqualTo(ScaffoldTier.TOOL_OWNED);
        assertThat(e.source()).isEqualTo("mvnw");
        assertThat(e.model()).isNull();
        assertThat(e.extras()).isEmpty();
    }

    @Test
    void parsesAllFourTierShapes() {
        String yaml = """
                schema: 1
                standards-version: "7"
                files:
                  - dest: mvnw
                    scope: project
                    tier: tool-owned
                    source: mvnw

                  - dest: .mvn/maven.config
                    scope: project
                    tier: tracked
                    source: .mvn/maven.config

                  - dest: .gitignore
                    scope: project
                    tier: tracked-block
                    source: .gitignore.ike-block
                    block-begin: "# BEGIN ike-managed"
                    block-end: "# END ike-managed"

                  - dest: "~/.m2/settings.xml"
                    scope: user
                    tier: model-managed
                    model: maven-settings-4
                    ensure:
                      pluginGroups:
                        - network.ike.tooling
                    never-touch:
                      - servers
                """;
        ScaffoldManifest m = ScaffoldManifestIo.read(
                new StringReader(yaml));
        assertThat(m.entries()).hasSize(4);

        ManifestEntry block = m.entries().get(2);
        assertThat(block.tier())
                .isEqualTo(ScaffoldTier.TRACKED_BLOCK);
        assertThat(block.extras())
                .containsEntry("block-begin", "# BEGIN ike-managed")
                .containsEntry("block-end", "# END ike-managed");

        ManifestEntry settings = m.entries().get(3);
        assertThat(settings.tier())
                .isEqualTo(ScaffoldTier.MODEL_MANAGED);
        assertThat(settings.model()).isEqualTo("maven-settings-4");
        assertThat(settings.extras()).containsKey("ensure");
        assertThat(settings.extras().get("never-touch"))
                .isInstanceOf(List.class);
    }

    @Test
    void entriesInScopeFiltersCorrectly() {
        String yaml = """
                schema: 1
                standards-version: "7"
                files:
                  - dest: mvnw
                    scope: project
                    tier: tool-owned
                    source: mvnw
                  - dest: "~/.m2/settings.xml"
                    scope: user
                    tier: model-managed
                    model: maven-settings-4
                """;
        ScaffoldManifest m = ScaffoldManifestIo.read(
                new StringReader(yaml));

        assertThat(m.entriesInScope(ScaffoldScope.PROJECT))
                .extracting(ManifestEntry::dest)
                .containsExactly("mvnw");
        assertThat(m.entriesInScope(ScaffoldScope.USER))
                .extracting(ManifestEntry::dest)
                .containsExactly("~/.m2/settings.xml");
    }

    @Test
    void missingStandardsVersionRejected() {
        String yaml = """
                schema: 1
                files: []
                """;
        assertThatThrownBy(() ->
                ScaffoldManifestIo.read(new StringReader(yaml)))
                .isInstanceOf(ScaffoldException.class)
                .hasMessageContaining("standards-version");
    }

    @Test
    void missingFilesRejected() {
        String yaml = """
                schema: 1
                standards-version: "7"
                """;
        assertThatThrownBy(() ->
                ScaffoldManifestIo.read(new StringReader(yaml)))
                .isInstanceOf(ScaffoldException.class)
                .hasMessageContaining("files");
    }

    @Test
    void unsupportedSchemaRejected() {
        String yaml = """
                schema: 99
                standards-version: "7"
                files: []
                """;
        assertThatThrownBy(() ->
                ScaffoldManifestIo.read(new StringReader(yaml)))
                .isInstanceOf(ScaffoldException.class)
                .hasMessageContaining("schema");
    }

    @Test
    void missingEntryFieldsCarryContext() {
        String yaml = """
                schema: 1
                standards-version: "7"
                files:
                  - scope: project
                    tier: tool-owned
                    source: mvnw
                """;
        assertThatThrownBy(() ->
                ScaffoldManifestIo.read(new StringReader(yaml)))
                .isInstanceOf(ScaffoldException.class)
                .hasMessageContaining("dest")
                .hasMessageContaining("files[0]");
    }

    @Test
    void sourceOnModelManagedEntryRejected() {
        String yaml = """
                schema: 1
                standards-version: "7"
                files:
                  - dest: "~/.m2/settings.xml"
                    scope: user
                    tier: model-managed
                    model: maven-settings-4
                    source: something
                """;
        assertThatThrownBy(() ->
                ScaffoldManifestIo.read(new StringReader(yaml)))
                .isInstanceOf(ScaffoldException.class)
                .hasMessageContaining("model-managed")
                .hasMessageContaining("source");
    }

    @Test
    void modelOnToolOwnedEntryRejected() {
        String yaml = """
                schema: 1
                standards-version: "7"
                files:
                  - dest: mvnw
                    scope: project
                    tier: tool-owned
                    source: mvnw
                    model: nope
                """;
        assertThatThrownBy(() ->
                ScaffoldManifestIo.read(new StringReader(yaml)))
                .isInstanceOf(ScaffoldException.class)
                .hasMessageContaining("model");
    }

    // ── Foundation section (#345) ───────────────────────────────────

    @Test
    void absentFoundationSection_returnsNullFoundation() {
        String yaml = """
                schema: 1
                standards-version: "7"
                files: []
                """;
        ScaffoldManifest manifest =
                ScaffoldManifestIo.read(new StringReader(yaml));
        assertThat(manifest.foundation()).isNull();
    }

    @Test
    void fullFoundationSection_parsesParentAndProperties() {
        String yaml = """
                schema: 1
                standards-version: "152"
                foundation:
                  parent:
                    groupId: network.ike.platform
                    artifactId: ike-parent
                    version: "35"
                  properties:
                    ike-tooling.version: "152"
                    ike-docs.version: "13"
                    ike-platform.version: "35"
                files: []
                """;
        ScaffoldManifest manifest =
                ScaffoldManifestIo.read(new StringReader(yaml));

        assertThat(manifest.foundation()).isNotNull();
        assertThat(manifest.foundation().parent()).isNotNull();
        assertThat(manifest.foundation().parent().groupId())
                .isEqualTo("network.ike.platform");
        assertThat(manifest.foundation().parent().artifactId())
                .isEqualTo("ike-parent");
        assertThat(manifest.foundation().parent().version())
                .isEqualTo("35");
        assertThat(manifest.foundation().properties())
                .containsEntry("ike-tooling.version", "152")
                .containsEntry("ike-docs.version", "13")
                .containsEntry("ike-platform.version", "35");
    }

    @Test
    void foundationPropertiesOnly_noParent() {
        // A scaffold could conceivably ship property pins without
        // parent pins (or vice versa). Parser should accept either.
        String yaml = """
                schema: 1
                standards-version: "152"
                foundation:
                  properties:
                    ike-tooling.version: "152"
                files: []
                """;
        ScaffoldManifest manifest =
                ScaffoldManifestIo.read(new StringReader(yaml));

        assertThat(manifest.foundation()).isNotNull();
        assertThat(manifest.foundation().parent()).isNull();
        assertThat(manifest.foundation().properties())
                .containsEntry("ike-tooling.version", "152");
    }

    @Test
    void foundationParentMissingField_rejected() {
        String yaml = """
                schema: 1
                standards-version: "152"
                foundation:
                  parent:
                    groupId: network.ike.platform
                    artifactId: ike-parent
                files: []
                """;
        assertThatThrownBy(() ->
                ScaffoldManifestIo.read(new StringReader(yaml)))
                .isInstanceOf(ScaffoldException.class)
                .hasMessageContaining("foundation.parent")
                .hasMessageContaining("version");
    }
}
