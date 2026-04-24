package network.ike.workspace;

import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VersionUpgradePlanWriterTest {

    // ── Header rendering ────────────────────────────────────────────

    @Test
    void writesHeaderFields() {
        VersionUpgradePlan plan = new VersionUpgradePlan(
                "1.0", "2026-04-24T14:32:00Z",
                VersionUpgradeScope.WORKSPACE,
                "sha256:abc", "sha256:def", "128-SNAPSHOT",
                Map.of());

        String yaml = VersionUpgradePlanWriter.toYaml(plan);

        assertThat(yaml)
                .contains("schema-version: \"1.0\"")
                .contains("generated: \"2026-04-24T14:32:00Z\"")
                .contains("scope: workspace")
                .contains("plan-hash: \"sha256:abc\"")
                .contains("pom-fingerprint: \"sha256:def\"")
                .contains("ike-tooling-version: \"128-SNAPSHOT\"");
    }

    @Test
    void omitsOptionalHeaderFieldsWhenNull() {
        VersionUpgradePlan plan = new VersionUpgradePlan(
                "1.0", null, VersionUpgradeScope.MODULE,
                null, null, null, Map.of());

        String yaml = VersionUpgradePlanWriter.toYaml(plan);

        assertThat(yaml)
                .contains("scope: module")
                .doesNotContain("generated:")
                .doesNotContain("plan-hash:")
                .doesNotContain("pom-fingerprint:")
                .doesNotContain("ike-tooling-version:");
    }

    @Test
    void writesEmptyNodesAsFlowStyle() {
        VersionUpgradePlan plan = new VersionUpgradePlan(
                "1.0", null, VersionUpgradeScope.WORKSPACE,
                null, null, null, Map.of());

        assertThat(VersionUpgradePlanWriter.toYaml(plan))
                .contains("nodes:\n  {}");
    }

    // ── Round-trip ──────────────────────────────────────────────────

    @Test
    void roundTrip_allThreeSections() {
        PropertyVersionUpgrade ready = new PropertyVersionUpgrade(
                "ike-tooling.version", "127-SNAPSHOT", "127",
                VersionUpgradeStatus.READY, null);
        PropertyVersionUpgrade blocked = new PropertyVersionUpgrade(
                "junit.version", "5.11.0", "6.0.0",
                VersionUpgradeStatus.BLOCKED, "major update disallowed");
        LiteralVersionUpgrade literal = new LiteralVersionUpgrade(
                "com.example", "legacy-lib", "dependency",
                "1.0", "1.1", VersionUpgradeStatus.READY, null);
        ParentVersionUpgrade parent = new ParentVersionUpgrade(
                "network.ike.platform", "ike-parent",
                "1", "2", VersionUpgradeStatus.READY, null);

        NodeVersionUpgrade node = new NodeVersionUpgrade(
                "ike-platform", parent,
                List.of(ready, blocked), List.of(literal));

        Map<String, NodeVersionUpgrade> nodes = new LinkedHashMap<>();
        nodes.put("ike-platform", node);

        VersionUpgradePlan original = new VersionUpgradePlan(
                "1.0", "2026-04-24T14:32:00Z",
                VersionUpgradeScope.WORKSPACE,
                "sha256:abc", "sha256:def", "128-SNAPSHOT",
                nodes);

        String yaml = VersionUpgradePlanWriter.toYaml(original);
        VersionUpgradePlan reparsed = VersionUpgradePlanReader.read(new StringReader(yaml));

        assertThat(reparsed.schemaVersion())
                .isEqualTo(original.schemaVersion());
        assertThat(reparsed.scope()).isEqualTo(original.scope());
        assertThat(reparsed.planHash()).isEqualTo(original.planHash());
        assertThat(reparsed.nodes()).containsOnlyKeys("ike-platform");

        NodeVersionUpgrade back = reparsed.nodes().get("ike-platform");
        assertThat(back.parent()).isEqualTo(parent);
        assertThat(back.properties()).containsExactly(ready, blocked);
        assertThat(back.literals()).containsExactly(literal);
    }

    @Test
    void roundTrip_pendingUpstream() {
        ParentVersionUpgrade pending = new ParentVersionUpgrade(
                "network.ike.platform", "ike-parent",
                "1", "[pending ike-platform release]",
                VersionUpgradeStatus.PENDING_UPSTREAM,
                "cascade dep on ike-platform");
        NodeVersionUpgrade node = new NodeVersionUpgrade(
                "doc-example", pending, List.of(), List.of());

        VersionUpgradePlan original = new VersionUpgradePlan(
                "1.0", null, VersionUpgradeScope.WORKSPACE,
                null, null, null,
                Map.of("doc-example", node));

        String yaml = VersionUpgradePlanWriter.toYaml(original);
        VersionUpgradePlan reparsed = VersionUpgradePlanReader.read(new StringReader(yaml));

        assertThat(reparsed.nodes().get("doc-example").parent())
                .isEqualTo(pending);
    }

    @Test
    void roundTrip_preservesNodeOrder() {
        Map<String, NodeVersionUpgrade> nodes = new LinkedHashMap<>();
        nodes.put("ike-docs",
                new NodeVersionUpgrade("ike-docs", null, List.of(), List.of()));
        nodes.put("ike-platform",
                new NodeVersionUpgrade("ike-platform", null, List.of(), List.of()));
        nodes.put("example-project",
                new NodeVersionUpgrade("example-project", null, List.of(), List.of()));

        VersionUpgradePlan original = new VersionUpgradePlan(
                "1.0", null, VersionUpgradeScope.WORKSPACE,
                null, null, null, nodes);

        String yaml = VersionUpgradePlanWriter.toYaml(original);
        VersionUpgradePlan reparsed = VersionUpgradePlanReader.read(new StringReader(yaml));

        assertThat(reparsed.nodes().keySet())
                .containsExactly("ike-docs", "ike-platform",
                        "example-project");
    }

    // ── Escaping ────────────────────────────────────────────────────

    @Test
    void escapesQuotesInReason() {
        PropertyVersionUpgrade p = new PropertyVersionUpgrade(
                "x.version", "1", "2", VersionUpgradeStatus.BLOCKED,
                "blocked by \"custom\" rule");
        NodeVersionUpgrade node = new NodeVersionUpgrade(
                "m", null, List.of(p), List.of());

        VersionUpgradePlan plan = new VersionUpgradePlan(
                "1.0", null, VersionUpgradeScope.MODULE,
                null, null, null, Map.of("m", node));

        String yaml = VersionUpgradePlanWriter.toYaml(plan);
        VersionUpgradePlan reparsed = VersionUpgradePlanReader.read(new StringReader(yaml));

        assertThat(reparsed.nodes().get("m").properties().getFirst().reason())
                .isEqualTo("blocked by \"custom\" rule");
    }
}
