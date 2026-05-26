package network.ike.plugin;

import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link PomRewriter} — focused on the
 * {@link PomRewriter#readParentVersion} read companion added for
 * IKE-Network/ike-issues#496 part E. Update-side methods are
 * exercised indirectly through their call sites in the scaffold and
 * cascade mojos.
 */
class PomRewriterTest {

    private static final String POM_WITH_PARENT = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <parent>
                    <groupId>network.ike</groupId>
                    <artifactId>ike-base-parent</artifactId>
                    <version>7</version>
                </parent>
                <groupId>network.ike.tooling</groupId>
                <artifactId>ike-tooling</artifactId>
                <version>199-SNAPSHOT</version>
            </project>
            """;

    @Test
    void readParentVersion_returns_version_when_coordinates_match() {
        assertThat(PomRewriter.readParentVersion(POM_WITH_PARENT,
                "network.ike", "ike-base-parent"))
                .contains("7");
    }

    @Test
    void readParentVersion_empty_when_groupId_differs() {
        assertThat(PomRewriter.readParentVersion(POM_WITH_PARENT,
                "org.example", "ike-base-parent"))
                .isEmpty();
    }

    @Test
    void readParentVersion_empty_when_artifactId_differs() {
        assertThat(PomRewriter.readParentVersion(POM_WITH_PARENT,
                "network.ike", "some-other-parent"))
                .isEmpty();
    }

    @Test
    void readParentVersion_empty_when_no_parent_block() {
        String pom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>network.ike</groupId>
                    <artifactId>ike-base-parent</artifactId>
                    <version>7</version>
                </project>
                """;

        assertThat(PomRewriter.readParentVersion(pom,
                "network.ike", "ike-base-parent"))
                .isEmpty();
    }

    @Test
    void readParentVersion_returns_unresolved_property_reference() {
        // The read returns the version text exactly as it appears —
        // a literal, a ${...} reference, anything. The alignment loop
        // is the one that decides what to do with placeholders.
        String pom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>network.ike</groupId>
                        <artifactId>ike-base-parent</artifactId>
                        <version>${network.ike·ike-base-parent}</version>
                    </parent>
                    <groupId>network.ike.tooling</groupId>
                    <artifactId>ike-tooling</artifactId>
                    <version>199-SNAPSHOT</version>
                </project>
                """;

        assertThat(PomRewriter.readParentVersion(pom,
                "network.ike", "ike-base-parent"))
                .contains("${network.ike·ike-base-parent}");
    }

    @Test
    void updateParentVersion_round_trips_through_readParentVersion() {
        String updated = PomRewriter.updateParentVersion(
                POM_WITH_PARENT, "network.ike", "ike-base-parent", "8");

        assertThat(PomRewriter.readParentVersion(updated,
                "network.ike", "ike-base-parent"))
                .contains("8");
    }

    // ── addProperty / removeProperty / listProperties (#527) ────────

    private static final String POM_WITH_PROPERTIES = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
                <modelVersion>4.0.0</modelVersion>
                <groupId>network.ike</groupId>
                <artifactId>ike-base-parent</artifactId>
                <version>15-SNAPSHOT</version>
                <properties>
                    <network.ike__GA__ike-base-parent__VERSION>${project.version}</network.ike__GA__ike-base-parent__VERSION>
                    <network.ike__GA__ike-base-parent__ALIAS>ike-base-parent.version</network.ike__GA__ike-base-parent__ALIAS>
                </properties>
            </project>
            """;

    @Test
    void addProperty_appends_new_property_to_properties_block() {
        String updated = PomRewriter.addProperty(POM_WITH_PROPERTIES,
                "ike-base-parent.version",
                "${network.ike__GA__ike-base-parent__VERSION}");

        assertThat(PomRewriter.listProperties(updated))
                .containsEntry("ike-base-parent.version",
                        "${network.ike__GA__ike-base-parent__VERSION}");
    }

    @Test
    void addProperty_noop_when_property_already_declared() {
        String updated = PomRewriter.addProperty(POM_WITH_PROPERTIES,
                "network.ike__GA__ike-base-parent__VERSION",
                "should-not-overwrite");

        // Value should not have changed
        assertThat(PomRewriter.listProperties(updated))
                .containsEntry("network.ike__GA__ike-base-parent__VERSION",
                        "${project.version}");
    }

    @Test
    void removeProperty_removes_declared_property() {
        String updated = PomRewriter.removeProperty(POM_WITH_PROPERTIES,
                "network.ike__GA__ike-base-parent__ALIAS");

        assertThat(PomRewriter.listProperties(updated))
                .doesNotContainKey("network.ike__GA__ike-base-parent__ALIAS")
                .containsKey("network.ike__GA__ike-base-parent__VERSION");
    }

    @Test
    void removeProperty_noop_when_property_absent() {
        String updated = PomRewriter.removeProperty(POM_WITH_PROPERTIES,
                "does-not-exist");

        assertThat(PomRewriter.listProperties(updated))
                .isEqualTo(PomRewriter.listProperties(POM_WITH_PROPERTIES));
    }

    @Test
    void listProperties_returns_properties_in_declaration_order() {
        Map<String, String> properties =
                PomRewriter.listProperties(POM_WITH_PROPERTIES);

        assertThat(properties).hasSize(2);
        assertThat(properties.keySet()).containsExactly(
                "network.ike__GA__ike-base-parent__VERSION",
                "network.ike__GA__ike-base-parent__ALIAS");
    }

    @Test
    void listProperties_empty_when_no_properties_block() {
        assertThat(PomRewriter.listProperties(POM_WITH_PARENT))
                .isEmpty();
    }

    @Test
    void addProperty_then_removeProperty_round_trips() {
        String afterAdd = PomRewriter.addProperty(POM_WITH_PROPERTIES,
                "my-alias.version",
                "${some-canonical}");
        assertThat(PomRewriter.listProperties(afterAdd))
                .containsKey("my-alias.version");

        String afterRemove = PomRewriter.removeProperty(afterAdd,
                "my-alias.version");
        assertThat(PomRewriter.listProperties(afterRemove))
                .doesNotContainKey("my-alias.version")
                .containsKey("network.ike__GA__ike-base-parent__VERSION");
    }
}
