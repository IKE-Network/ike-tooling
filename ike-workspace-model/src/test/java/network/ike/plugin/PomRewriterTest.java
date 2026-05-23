package network.ike.plugin;

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
}
