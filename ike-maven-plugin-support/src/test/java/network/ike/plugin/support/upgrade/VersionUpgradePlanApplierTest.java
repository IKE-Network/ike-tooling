package network.ike.plugin.support.upgrade;

import network.ike.workspace.LiteralVersionUpgrade;
import network.ike.workspace.NodeVersionUpgrade;
import network.ike.workspace.ParentVersionUpgrade;
import network.ike.workspace.PropertyVersionUpgrade;
import network.ike.workspace.VersionUpgradeStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VersionUpgradePlanApplierTest {

    // ── Parent ──────────────────────────────────────────────────────

    @Test
    void parentVersionIsUpdated() {
        String pom = """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>network.ike.platform</groupId>
                    <artifactId>ike-parent</artifactId>
                    <version>2</version>
                  </parent>
                  <artifactId>x</artifactId>
                </project>
                """;
        NodeVersionUpgrade node = new NodeVersionUpgrade("x",
                new ParentVersionUpgrade("network.ike.platform",
                        "ike-parent", "2", "3",
                        VersionUpgradeStatus.READY, null),
                List.of(), List.of());

        VersionUpgradePlanApplier.ApplyResult result =
                VersionUpgradePlanApplier.apply(pom, node);

        assertThat(result.edits()).isEqualTo(1);
        assertThat(result.text()).contains("<version>3</version>");
        assertThat(result.text()).doesNotContain("<version>2</version>");
    }

    @Test
    void blockedParentEntryIsSkipped() {
        String pom = """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>g</groupId>
                    <artifactId>a</artifactId>
                    <version>2</version>
                  </parent>
                  <artifactId>x</artifactId>
                </project>
                """;
        NodeVersionUpgrade node = new NodeVersionUpgrade("x",
                new ParentVersionUpgrade("g", "a", "2", "2",
                        VersionUpgradeStatus.BLOCKED, "frozen"),
                List.of(), List.of());

        VersionUpgradePlanApplier.ApplyResult result =
                VersionUpgradePlanApplier.apply(pom, node);

        assertThat(result.edits()).isZero();
        assertThat(result.text()).contains("<version>2</version>");
    }

    @Test
    void parentMismatchAborts() {
        String pom = """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>g</groupId>
                    <artifactId>a</artifactId>
                    <version>2</version>
                  </parent>
                  <artifactId>x</artifactId>
                </project>
                """;
        NodeVersionUpgrade node = new NodeVersionUpgrade("x",
                new ParentVersionUpgrade("g", "different", "2", "3",
                        VersionUpgradeStatus.READY, null),
                List.of(), List.of());

        assertThatThrownBy(() ->
                VersionUpgradePlanApplier.apply(pom, node))
                .isInstanceOf(VersionUpgradeApplyException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    void parentVersionMismatchAborts() {
        String pom = """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>g</groupId>
                    <artifactId>a</artifactId>
                    <version>5</version>
                  </parent>
                  <artifactId>x</artifactId>
                </project>
                """;
        NodeVersionUpgrade node = new NodeVersionUpgrade("x",
                new ParentVersionUpgrade("g", "a", "2", "3",
                        VersionUpgradeStatus.READY, null),
                List.of(), List.of());

        assertThatThrownBy(() ->
                VersionUpgradePlanApplier.apply(pom, node))
                .isInstanceOf(VersionUpgradeApplyException.class)
                .hasMessageContaining("does not match");
    }

    // ── Properties ──────────────────────────────────────────────────

    @Test
    void propertyValueIsUpdated() {
        String pom = """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <artifactId>x</artifactId>
                  <properties>
                    <ike-tooling.version>127</ike-tooling.version>
                    <other.thing>kept</other.thing>
                  </properties>
                </project>
                """;
        NodeVersionUpgrade node = new NodeVersionUpgrade("x", null,
                List.of(new PropertyVersionUpgrade(
                        "ike-tooling.version", "127", "128",
                        VersionUpgradeStatus.READY, null)),
                List.of());

        VersionUpgradePlanApplier.ApplyResult result =
                VersionUpgradePlanApplier.apply(pom, node);

        assertThat(result.edits()).isEqualTo(1);
        assertThat(result.text()).contains(
                "<ike-tooling.version>128</ike-tooling.version>");
        assertThat(result.text()).contains(
                "<other.thing>kept</other.thing>");
    }

    @Test
    void propertyMismatchAborts() {
        String pom = """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <artifactId>x</artifactId>
                  <properties>
                    <ike-tooling.version>129</ike-tooling.version>
                  </properties>
                </project>
                """;
        NodeVersionUpgrade node = new NodeVersionUpgrade("x", null,
                List.of(new PropertyVersionUpgrade(
                        "ike-tooling.version", "127", "128",
                        VersionUpgradeStatus.READY, null)),
                List.of());

        assertThatThrownBy(() ->
                VersionUpgradePlanApplier.apply(pom, node))
                .isInstanceOf(VersionUpgradeApplyException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    void propertyMissingAborts() {
        String pom = """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <artifactId>x</artifactId>
                  <properties>
                    <other>thing</other>
                  </properties>
                </project>
                """;
        NodeVersionUpgrade node = new NodeVersionUpgrade("x", null,
                List.of(new PropertyVersionUpgrade(
                        "ike-tooling.version", "127", "128",
                        VersionUpgradeStatus.READY, null)),
                List.of());

        assertThatThrownBy(() ->
                VersionUpgradePlanApplier.apply(pom, node))
                .isInstanceOf(VersionUpgradeApplyException.class)
                .hasMessageContaining("ike-tooling.version");
    }

    // ── Literals ────────────────────────────────────────────────────

    @Test
    void literalPluginVersionIsUpdated() {
        // The ike-parent extensions=true case.
        String pom = """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <artifactId>ike-parent</artifactId>
                  <build>
                    <pluginManagement>
                      <plugins>
                        <plugin>
                          <groupId>network.ike.docs</groupId>
                          <artifactId>ike-doc-maven-plugin</artifactId>
                          <version>1</version>
                          <extensions>true</extensions>
                        </plugin>
                      </plugins>
                    </pluginManagement>
                  </build>
                </project>
                """;
        NodeVersionUpgrade node = new NodeVersionUpgrade("ike-parent",
                null, List.of(),
                List.of(new LiteralVersionUpgrade(
                        "network.ike.docs", "ike-doc-maven-plugin",
                        "main/build/pluginManagement", "1", "2",
                        VersionUpgradeStatus.READY, null)));

        VersionUpgradePlanApplier.ApplyResult result =
                VersionUpgradePlanApplier.apply(pom, node);

        assertThat(result.edits()).isEqualTo(1);
        assertThat(result.text()).contains("<version>2</version>");
        assertThat(result.text()).contains("<extensions>true</extensions>");
    }

    @Test
    void literalMissingAborts() {
        String pom = """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <artifactId>x</artifactId>
                </project>
                """;
        NodeVersionUpgrade node = new NodeVersionUpgrade("x", null,
                List.of(),
                List.of(new LiteralVersionUpgrade(
                        "g", "a", "main/build", "1", "2",
                        VersionUpgradeStatus.READY, null)));

        assertThatThrownBy(() ->
                VersionUpgradePlanApplier.apply(pom, node))
                .isInstanceOf(VersionUpgradeApplyException.class)
                .hasMessageContaining("no matching");
    }

    // ── Combined ────────────────────────────────────────────────────

    @Test
    void parentPlusPropertyAppliedTogether() {
        String pom = """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>network.ike.platform</groupId>
                    <artifactId>ike-parent</artifactId>
                    <version>2</version>
                  </parent>
                  <artifactId>x</artifactId>
                  <properties>
                    <ike-tooling.version>127</ike-tooling.version>
                  </properties>
                </project>
                """;
        NodeVersionUpgrade node = new NodeVersionUpgrade("x",
                new ParentVersionUpgrade("network.ike.platform",
                        "ike-parent", "2", "3",
                        VersionUpgradeStatus.READY, null),
                List.of(new PropertyVersionUpgrade(
                        "ike-tooling.version", "127", "128",
                        VersionUpgradeStatus.READY, null)),
                List.of());

        VersionUpgradePlanApplier.ApplyResult result =
                VersionUpgradePlanApplier.apply(pom, node);

        assertThat(result.edits()).isEqualTo(2);
        assertThat(result.text()).contains(
                "<ike-tooling.version>128</ike-tooling.version>");
        // Parent version updated to 3.
        assertThat(result.text()).containsPattern(
                "<parent>[\\s\\S]*<version>3</version>");
    }

    @Test
    void commentsArePreserved() {
        String pom = """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <artifactId>x</artifactId>
                  <properties>
                    <!-- IKE Tooling release version, bumped by ws:versions-upgrade -->
                    <ike-tooling.version>127</ike-tooling.version>
                  </properties>
                </project>
                """;
        NodeVersionUpgrade node = new NodeVersionUpgrade("x", null,
                List.of(new PropertyVersionUpgrade(
                        "ike-tooling.version", "127", "128",
                        VersionUpgradeStatus.READY, null)),
                List.of());

        VersionUpgradePlanApplier.ApplyResult result =
                VersionUpgradePlanApplier.apply(pom, node);

        assertThat(result.text()).contains(
                "<!-- IKE Tooling release version, bumped by "
                        + "ws:versions-upgrade -->");
        assertThat(result.text()).contains(
                "<ike-tooling.version>128</ike-tooling.version>");
    }
}
