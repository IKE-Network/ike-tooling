package network.ike.plugin.support.upgrade;

import network.ike.workspace.NodeVersionUpgrade;
import network.ike.workspace.VersionUpgradePlan;
import network.ike.workspace.VersionUpgradeRule;
import network.ike.workspace.VersionUpgradeRules;
import network.ike.workspace.VersionUpgradeScope;
import network.ike.workspace.VersionUpgradeStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VersionUpgradePlanBuilderTest {

    /** Build a fake resolver from a coord → versions map. */
    private static CandidateVersionResolver fakeResolver(
            Map<String, List<String>> versions) {
        return (g, a, current) -> versions.getOrDefault(g + ":" + a,
                List.of());
    }

    private static Path writePom(Path dir, String content) throws IOException {
        Path p = dir.resolve("pom.xml");
        Files.writeString(p, content);
        return p;
    }

    // ── Parent ──────────────────────────────────────────────────────

    @Test
    void allowedParentUpgradesToHighestCandidate(@TempDir Path tmp)
            throws IOException {
        Path pom = writePom(tmp, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>network.ike.platform</groupId>
                    <artifactId>ike-parent</artifactId>
                    <version>2</version>
                  </parent>
                  <artifactId>example</artifactId>
                </project>
                """);

        VersionUpgradeRules rules = VersionUpgradeRules.allowAll();
        CandidateVersionResolver resolver = fakeResolver(Map.of(
                "network.ike.platform:ike-parent",
                List.of("1", "2", "3")));

        VersionUpgradePlan plan = new VersionUpgradePlanBuilder(rules, resolver)
                .buildModulePlan("example", pom, "128-SNAPSHOT");

        assertThat(plan.scope()).isEqualTo(VersionUpgradeScope.MODULE);
        NodeVersionUpgrade node = plan.nodes().get("example");
        assertThat(node.parent().fromVersion()).isEqualTo("2");
        assertThat(node.parent().toVersion()).isEqualTo("3");
        assertThat(node.parent().status()).isEqualTo(
                VersionUpgradeStatus.READY);
    }

    @Test
    void parentAlreadyAtLatestProducesNoEntry(@TempDir Path tmp)
            throws IOException {
        Path pom = writePom(tmp, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>g</groupId>
                    <artifactId>a</artifactId>
                    <version>3</version>
                  </parent>
                  <artifactId>x</artifactId>
                </project>
                """);

        CandidateVersionResolver resolver = fakeResolver(Map.of(
                "g:a", List.of("1", "2", "3")));

        VersionUpgradePlan plan = new VersionUpgradePlanBuilder(
                VersionUpgradeRules.allowAll(), resolver)
                .buildModulePlan("x", pom, null);

        assertThat(plan.nodes().get("x").parent()).isNull();
    }

    @Test
    void blockedParentEmitsBlockedEntry(@TempDir Path tmp)
            throws IOException {
        Path pom = writePom(tmp, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>g</groupId>
                    <artifactId>a</artifactId>
                    <version>3</version>
                  </parent>
                  <artifactId>x</artifactId>
                </project>
                """);

        VersionUpgradeRules rules = new VersionUpgradeRules("1.0",
                VersionUpgradeRule.Action.BLOCK,
                List.of(new VersionUpgradeRule("g", "a",
                        VersionUpgradeRule.Action.BLOCK, null,
                        "frozen for compatibility")));
        CandidateVersionResolver resolver = fakeResolver(Map.of(
                "g:a", List.of("1", "2", "3", "4")));

        VersionUpgradePlan plan = new VersionUpgradePlanBuilder(rules, resolver)
                .buildModulePlan("x", pom, null);

        assertThat(plan.nodes().get("x").parent().status())
                .isEqualTo(VersionUpgradeStatus.BLOCKED);
        assertThat(plan.nodes().get("x").parent().reason())
                .isEqualTo("frozen for compatibility");
        assertThat(plan.nodes().get("x").parent().toVersion())
                .isEqualTo("3"); // unchanged
    }

    @Test
    void pinnedParentUpgradesToPinnedVersion(@TempDir Path tmp)
            throws IOException {
        Path pom = writePom(tmp, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>g</groupId>
                    <artifactId>a</artifactId>
                    <version>2</version>
                  </parent>
                  <artifactId>x</artifactId>
                </project>
                """);

        VersionUpgradeRules rules = new VersionUpgradeRules("1.0",
                VersionUpgradeRule.Action.BLOCK,
                List.of(new VersionUpgradeRule("g", "a",
                        VersionUpgradeRule.Action.PIN, "2.5",
                        "release-pinned")));
        CandidateVersionResolver resolver = fakeResolver(Map.of(
                "g:a", List.of("1", "2", "3", "4")));

        VersionUpgradePlan plan = new VersionUpgradePlanBuilder(rules, resolver)
                .buildModulePlan("x", pom, null);

        assertThat(plan.nodes().get("x").parent().toVersion())
                .isEqualTo("2.5");
        assertThat(plan.nodes().get("x").parent().status())
                .isEqualTo(VersionUpgradeStatus.READY);
    }

    // ── Properties ──────────────────────────────────────────────────

    @Test
    void allowedPropertyUpgrades(@TempDir Path tmp) throws IOException {
        Path pom = writePom(tmp, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <artifactId>x</artifactId>
                  <properties>
                    <ike-tooling.version>127</ike-tooling.version>
                  </properties>
                  <dependencies>
                    <dependency>
                      <groupId>network.ike.tooling</groupId>
                      <artifactId>ike-workspace-model</artifactId>
                      <version>${ike-tooling.version}</version>
                    </dependency>
                  </dependencies>
                </project>
                """);

        CandidateVersionResolver resolver = fakeResolver(Map.of(
                "network.ike.tooling:ike-workspace-model",
                List.of("125", "126", "127", "128")));

        VersionUpgradePlan plan = new VersionUpgradePlanBuilder(
                VersionUpgradeRules.allowAll(), resolver)
                .buildModulePlan("x", pom, "128-SNAPSHOT");

        assertThat(plan.nodes().get("x").properties()).hasSize(1);
        assertThat(plan.nodes().get("x").properties().get(0).propertyName())
                .isEqualTo("ike-tooling.version");
        assertThat(plan.nodes().get("x").properties().get(0).fromVersion())
                .isEqualTo("127");
        assertThat(plan.nodes().get("x").properties().get(0).toVersion())
                .isEqualTo("128");
    }

    @Test
    void propertyAlreadyAtLatestProducesNoEntry(@TempDir Path tmp)
            throws IOException {
        Path pom = writePom(tmp, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <artifactId>x</artifactId>
                  <properties>
                    <ike-tooling.version>128</ike-tooling.version>
                  </properties>
                  <dependencies>
                    <dependency>
                      <groupId>network.ike.tooling</groupId>
                      <artifactId>ike-workspace-model</artifactId>
                      <version>${ike-tooling.version}</version>
                    </dependency>
                  </dependencies>
                </project>
                """);

        CandidateVersionResolver resolver = fakeResolver(Map.of(
                "network.ike.tooling:ike-workspace-model",
                List.of("127", "128")));

        VersionUpgradePlan plan = new VersionUpgradePlanBuilder(
                VersionUpgradeRules.allowAll(), resolver)
                .buildModulePlan("x", pom, null);

        assertThat(plan.nodes().get("x").properties()).isEmpty();
    }

    @Test
    void conflictingPropertyConsumersBlocked(@TempDir Path tmp)
            throws IOException {
        Path pom = writePom(tmp, """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <artifactId>x</artifactId>
                  <properties>
                    <shared.version>1.0</shared.version>
                  </properties>
                  <dependencies>
                    <dependency>
                      <groupId>g1</groupId>
                      <artifactId>a1</artifactId>
                      <version>${shared.version}</version>
                    </dependency>
                    <dependency>
                      <groupId>g2</groupId>
                      <artifactId>a2</artifactId>
                      <version>${shared.version}</version>
                    </dependency>
                  </dependencies>
                </project>
                """);

        // g1 wants 2.0, g2 wants 3.0 — conflict.
        CandidateVersionResolver resolver = fakeResolver(Map.of(
                "g1:a1", List.of("1.0", "2.0"),
                "g2:a2", List.of("1.0", "3.0")));

        VersionUpgradePlan plan = new VersionUpgradePlanBuilder(
                VersionUpgradeRules.allowAll(), resolver)
                .buildModulePlan("x", pom, null);

        assertThat(plan.nodes().get("x").properties()).hasSize(1);
        assertThat(plan.nodes().get("x").properties().get(0).status())
                .isEqualTo(VersionUpgradeStatus.BLOCKED);
        assertThat(plan.nodes().get("x").properties().get(0).reason())
                .contains("disagree");
    }

    // ── Literals ────────────────────────────────────────────────────

    @Test
    void allowedLiteralPluginUpgrades(@TempDir Path tmp) throws IOException {
        // Models the ike-parent extensions=true case: literal version
        // because Maven loads extensions before property interpolation.
        Path pom = writePom(tmp, """
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
                        </plugin>
                      </plugins>
                    </pluginManagement>
                  </build>
                </project>
                """);

        CandidateVersionResolver resolver = fakeResolver(Map.of(
                "network.ike.docs:ike-doc-maven-plugin",
                List.of("1", "2")));

        VersionUpgradePlan plan = new VersionUpgradePlanBuilder(
                VersionUpgradeRules.allowAll(), resolver)
                .buildModulePlan("ike-parent", pom, null);

        assertThat(plan.nodes().get("ike-parent").literals()).hasSize(1);
        assertThat(plan.nodes().get("ike-parent").literals().get(0).fromVersion())
                .isEqualTo("1");
        assertThat(plan.nodes().get("ike-parent").literals().get(0).toVersion())
                .isEqualTo("2");
    }

    // ── Workspace plan ──────────────────────────────────────────────

    @Test
    void workspacePlanContainsOneNodePerPom(@TempDir Path tmp)
            throws IOException {
        Path pomA = writePom(Files.createDirectory(tmp.resolve("a")),
                """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>g</groupId>
                    <artifactId>p</artifactId>
                    <version>1</version>
                  </parent>
                  <artifactId>a</artifactId>
                </project>
                """);
        Path pomB = writePom(Files.createDirectory(tmp.resolve("b")),
                """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>g</groupId>
                    <artifactId>p</artifactId>
                    <version>2</version>
                  </parent>
                  <artifactId>b</artifactId>
                </project>
                """);

        CandidateVersionResolver resolver = fakeResolver(Map.of(
                "g:p", List.of("1", "2", "3")));

        Map<String, Path> nodes = new LinkedHashMap<>();
        nodes.put("a", pomA);
        nodes.put("b", pomB);

        VersionUpgradePlan plan = new VersionUpgradePlanBuilder(
                VersionUpgradeRules.allowAll(), resolver)
                .buildWorkspacePlan(nodes, "128-SNAPSHOT");

        assertThat(plan.scope()).isEqualTo(VersionUpgradeScope.WORKSPACE);
        assertThat(plan.nodes()).containsOnlyKeys("a", "b");
        assertThat(plan.nodes().get("a").parent().toVersion()).isEqualTo("3");
        assertThat(plan.nodes().get("b").parent().toVersion()).isEqualTo("3");
    }

    // ── Fingerprint ─────────────────────────────────────────────────

    @Test
    void differentPomContentProducesDifferentFingerprint(@TempDir Path tmp)
            throws IOException {
        Path pomA = writePom(Files.createDirectory(tmp.resolve("a")),
                "<project><modelVersion>4.0.0</modelVersion>"
                        + "<artifactId>a</artifactId></project>");
        Path pomB = writePom(Files.createDirectory(tmp.resolve("b")),
                "<project><modelVersion>4.0.0</modelVersion>"
                        + "<artifactId>b</artifactId></project>");

        VersionUpgradePlanBuilder builder = new VersionUpgradePlanBuilder(
                VersionUpgradeRules.allowAll(),
                fakeResolver(Map.of()));

        String fpA = builder.buildModulePlan("a", pomA, null).pomFingerprint();
        String fpB = builder.buildModulePlan("b", pomB, null).pomFingerprint();

        assertThat(fpA).isNotEqualTo(fpB);
        assertThat(fpA).startsWith("sha256:");
    }
}
