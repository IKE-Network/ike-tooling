package network.ike.plugin.scaffold;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link FoundationDriftChecker} (#345).
 */
class FoundationDriftCheckerTest {

    private static ScaffoldManifest.Foundation foundationAt(
            String parentVersion,
            String ikeTooling,
            String ikeDocs,
            String ikePlatform) {
        return new ScaffoldManifest.Foundation(
                new ScaffoldManifest.ParentRef(
                        "network.ike.platform", "ike-parent", parentVersion),
                Map.of(
                        "ike-tooling.version", ikeTooling,
                        "ike-docs.version", ikeDocs,
                        "ike-platform.version", ikePlatform));
    }

    @Test
    void check_nullFoundation_returnsEmpty() {
        assertThat(FoundationDriftChecker.check("<project/>", null)).isEmpty();
    }

    @Test
    void check_alignedProject_allEntriesAligned() {
        String pom = """
                <project>
                  <parent>
                    <groupId>network.ike.platform</groupId>
                    <artifactId>ike-parent</artifactId>
                    <version>35</version>
                  </parent>
                  <properties>
                    <ike-tooling.version>151</ike-tooling.version>
                    <ike-docs.version>13</ike-docs.version>
                    <ike-platform.version>35</ike-platform.version>
                  </properties>
                </project>
                """;
        var foundation = foundationAt("35", "151", "13", "35");
        List<FoundationDriftChecker.Entry> entries =
                FoundationDriftChecker.check(pom, foundation);

        assertThat(entries).allMatch(e ->
                e.state() == FoundationDriftChecker.State.ALIGNED);
    }

    @Test
    void check_versionDrift_reportedAsDiffers() {
        // Project is on ike-tooling 150 but foundation pins 151.
        String pom = """
                <project>
                  <parent>
                    <groupId>network.ike.platform</groupId>
                    <artifactId>ike-parent</artifactId>
                    <version>35</version>
                  </parent>
                  <properties>
                    <ike-tooling.version>150</ike-tooling.version>
                    <ike-docs.version>13</ike-docs.version>
                    <ike-platform.version>35</ike-platform.version>
                  </properties>
                </project>
                """;
        var foundation = foundationAt("35", "151", "13", "35");
        List<FoundationDriftChecker.Entry> drifted =
                FoundationDriftChecker.check(pom, foundation).stream()
                        .filter(FoundationDriftChecker.Entry::isDrifted)
                        .toList();

        assertThat(drifted).hasSize(1);
        assertThat(drifted.get(0).name()).isEqualTo("ike-tooling.version");
        assertThat(drifted.get(0).actual()).isEqualTo("150");
        assertThat(drifted.get(0).expected()).isEqualTo("151");
        assertThat(drifted.get(0).state())
                .isEqualTo(FoundationDriftChecker.State.DIFFERS);
    }

    @Test
    void check_propertyAbsent_reportedAsAbsent() {
        // Project doesn't declare ike-tooling.version at all
        // (perhaps inherits via ike-parent).
        String pom = """
                <project>
                  <parent>
                    <groupId>network.ike.platform</groupId>
                    <artifactId>ike-parent</artifactId>
                    <version>35</version>
                  </parent>
                  <properties>
                    <java.version>25</java.version>
                  </properties>
                </project>
                """;
        var foundation = foundationAt("35", "151", "13", "35");
        List<FoundationDriftChecker.Entry> entries =
                FoundationDriftChecker.check(pom, foundation);

        FoundationDriftChecker.Entry ikeTooling = entries.stream()
                .filter(e -> e.name().equals("ike-tooling.version"))
                .findFirst().orElseThrow();
        assertThat(ikeTooling.state())
                .isEqualTo(FoundationDriftChecker.State.ABSENT);
    }

    @Test
    void check_parentVersionDrift_reported() {
        String pom = """
                <project>
                  <parent>
                    <groupId>network.ike.platform</groupId>
                    <artifactId>ike-parent</artifactId>
                    <version>33</version>
                  </parent>
                  <properties>
                    <ike-tooling.version>151</ike-tooling.version>
                    <ike-docs.version>13</ike-docs.version>
                    <ike-platform.version>35</ike-platform.version>
                  </properties>
                </project>
                """;
        var foundation = foundationAt("35", "151", "13", "35");
        FoundationDriftChecker.Entry parent =
                FoundationDriftChecker.check(pom, foundation).stream()
                        .filter(e -> e.kind() == FoundationDriftChecker.Kind.PARENT)
                        .findFirst().orElseThrow();

        assertThat(parent.actual()).isEqualTo("33");
        assertThat(parent.expected()).isEqualTo("35");
        assertThat(parent.state())
                .isEqualTo(FoundationDriftChecker.State.DIFFERS);
    }

    @Test
    void check_differentParentGa_returnsAbsent() {
        // Project inherits a different parent (not ike-parent) —
        // the parent comparison's actual value is null since the
        // GA doesn't match.
        String pom = """
                <project>
                  <parent>
                    <groupId>org.apache</groupId>
                    <artifactId>apache</artifactId>
                    <version>34</version>
                  </parent>
                </project>
                """;
        var foundation = foundationAt("35", "151", "13", "35");
        FoundationDriftChecker.Entry parent =
                FoundationDriftChecker.check(pom, foundation).stream()
                        .filter(e -> e.kind() == FoundationDriftChecker.Kind.PARENT)
                        .findFirst().orElseThrow();

        assertThat(parent.state())
                .isEqualTo(FoundationDriftChecker.State.ABSENT);
    }

    @Test
    void extractProperties_handlesMultipleEntries() {
        String pom = """
                <project>
                  <properties>
                    <a>1</a>
                    <b.version>2</b.version>
                    <c-name>3</c-name>
                  </properties>
                </project>
                """;
        Map<String, String> props =
                FoundationDriftChecker.extractProperties(pom);
        assertThat(props)
                .containsEntry("a", "1")
                .containsEntry("b.version", "2")
                .containsEntry("c-name", "3");
    }
}
