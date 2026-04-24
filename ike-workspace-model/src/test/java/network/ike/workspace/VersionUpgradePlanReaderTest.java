package network.ike.workspace;

import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VersionUpgradePlanReaderTest {

    // ── Header fields ───────────────────────────────────────────────

    @Test
    void parsesHeaderFields() {
        VersionUpgradePlan plan = VersionUpgradePlanReader.read(new StringReader("""
                schema-version: "1.0"
                generated: "2026-04-24T14:32:00Z"
                scope: workspace
                plan-hash: "sha256:abc"
                pom-fingerprint: "sha256:def"
                ike-tooling-version: "128-SNAPSHOT"
                nodes: {}
                """));

        assertThat(plan.schemaVersion()).isEqualTo("1.0");
        assertThat(plan.generated()).isEqualTo("2026-04-24T14:32:00Z");
        assertThat(plan.scope()).isEqualTo(VersionUpgradeScope.WORKSPACE);
        assertThat(plan.planHash()).isEqualTo("sha256:abc");
        assertThat(plan.pomFingerprint()).isEqualTo("sha256:def");
        assertThat(plan.ikeToolingVersion()).isEqualTo("128-SNAPSHOT");
        assertThat(plan.nodes()).isEmpty();
    }

    @Test
    void parsesModuleScope() {
        VersionUpgradePlan plan = VersionUpgradePlanReader.read(new StringReader("""
                schema-version: "1.0"
                scope: module
                nodes: {}
                """));

        assertThat(plan.scope()).isEqualTo(VersionUpgradeScope.MODULE);
    }

    @Test
    void rejectsMissingScope() {
        assertThatThrownBy(() -> VersionUpgradePlanReader.read(new StringReader("""
                schema-version: "1.0"
                nodes: {}
                """)))
                .isInstanceOf(VersionUpgradePlanException.class)
                .hasMessageContaining("scope");
    }

    @Test
    void rejectsUnknownScope() {
        assertThatThrownBy(() -> VersionUpgradePlanReader.read(new StringReader("""
                scope: galactic
                nodes: {}
                """)))
                .isInstanceOf(VersionUpgradePlanException.class)
                .hasMessageContaining("galactic");
    }

    @Test
    void rejectsEmptyInput() {
        assertThatThrownBy(() -> VersionUpgradePlanReader.read(new StringReader("")))
                .isInstanceOf(VersionUpgradePlanException.class)
                .hasMessageContaining("Empty");
    }

    // ── Node structure ──────────────────────────────────────────────

    @Test
    void parsesNodeWithAllThreeSections() {
        VersionUpgradePlan plan = VersionUpgradePlanReader.read(new StringReader("""
                scope: workspace
                nodes:
                  ike-platform:
                    parent:
                      groupId: "network.ike.platform"
                      artifactId: "ike-parent"
                      from: "1"
                      to: "2"
                      status: ready
                    properties:
                      ike-tooling.version:
                        from: "127-SNAPSHOT"
                        to: "127"
                        status: ready
                    literals:
                      - groupId: "com.example"
                        artifactId: "legacy-lib"
                        location: "dependency"
                        from: "1.0"
                        to: "1.1"
                        status: ready
                """));

        NodeVersionUpgrade node = plan.nodes().get("ike-platform");
        assertThat(node).isNotNull();
        assertThat(node.nodeName()).isEqualTo("ike-platform");
        assertThat(node.parent()).isNotNull();
        assertThat(node.parent().fromVersion()).isEqualTo("1");
        assertThat(node.parent().toVersion()).isEqualTo("2");
        assertThat(node.properties()).hasSize(1);
        assertThat(node.properties().getFirst().propertyName())
                .isEqualTo("ike-tooling.version");
        assertThat(node.literals()).hasSize(1);
        assertThat(node.literals().getFirst().artifactId())
                .isEqualTo("legacy-lib");
    }

    @Test
    void parsesNodeWithNullParent() {
        VersionUpgradePlan plan = VersionUpgradePlanReader.read(new StringReader("""
                scope: module
                nodes:
                  ike-parent:
                    parent: null
                    properties: {}
                    literals: []
                """));

        assertThat(plan.nodes().get("ike-parent").parent()).isNull();
    }

    @Test
    void preservesNodeInsertionOrder() {
        VersionUpgradePlan plan = VersionUpgradePlanReader.read(new StringReader("""
                scope: workspace
                nodes:
                  ike-docs:
                    parent: null
                    properties: {}
                    literals: []
                  ike-platform:
                    parent: null
                    properties: {}
                    literals: []
                  example-project:
                    parent: null
                    properties: {}
                    literals: []
                """));

        assertThat(plan.nodes().keySet())
                .containsExactly("ike-docs", "ike-platform",
                        "example-project");
    }

    // ── Status parsing ──────────────────────────────────────────────

    @Test
    void parsesAllStatusValues() {
        VersionUpgradePlan plan = VersionUpgradePlanReader.read(new StringReader("""
                scope: module
                nodes:
                  m:
                    parent: null
                    properties:
                      a.version:
                        from: "1"
                        to: "2"
                        status: ready
                      b.version:
                        from: "1"
                        to: "3"
                        status: blocked
                        reason: "major update"
                      c.version:
                        from: "1"
                        to: "[pending upstream]"
                        status: pending-upstream
                    literals: []
                """));

        var props = plan.nodes().get("m").properties();
        assertThat(props).extracting(PropertyVersionUpgrade::status)
                .containsExactly(VersionUpgradeStatus.READY,
                        VersionUpgradeStatus.BLOCKED,
                        VersionUpgradeStatus.PENDING_UPSTREAM);
        assertThat(props.get(1).reason()).isEqualTo("major update");
    }

    @Test
    void rejectsUnknownStatus() {
        assertThatThrownBy(() -> VersionUpgradePlanReader.read(new StringReader("""
                scope: module
                nodes:
                  m:
                    parent: null
                    properties:
                      x.version:
                        from: "1"
                        to: "2"
                        status: maybe
                    literals: []
                """)))
                .isInstanceOf(VersionUpgradePlanException.class)
                .hasMessageContaining("maybe");
    }

    // ── Required-field validation ───────────────────────────────────

    @Test
    void rejectsPropertyMissingFrom() {
        assertThatThrownBy(() -> VersionUpgradePlanReader.read(new StringReader("""
                scope: module
                nodes:
                  m:
                    parent: null
                    properties:
                      x.version:
                        to: "2"
                        status: ready
                    literals: []
                """)))
                .isInstanceOf(VersionUpgradePlanException.class)
                .hasMessageContaining("from");
    }

    @Test
    void rejectsLiteralMissingArtifactId() {
        assertThatThrownBy(() -> VersionUpgradePlanReader.read(new StringReader("""
                scope: module
                nodes:
                  m:
                    parent: null
                    properties: {}
                    literals:
                      - groupId: "com.example"
                        from: "1"
                        to: "2"
                        status: ready
                """)))
                .isInstanceOf(VersionUpgradePlanException.class)
                .hasMessageContaining("artifactId");
    }

    @Test
    void rejectsParentMissingStatus() {
        assertThatThrownBy(() -> VersionUpgradePlanReader.read(new StringReader("""
                scope: module
                nodes:
                  m:
                    parent:
                      groupId: "g"
                      artifactId: "a"
                      from: "1"
                      to: "2"
                    properties: {}
                    literals: []
                """)))
                .isInstanceOf(VersionUpgradePlanException.class)
                .hasMessageContaining("status");
    }
}
