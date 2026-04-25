package network.ike.workspace;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VersionUpgradeRulesTest {

    @Test
    void firstMatchingRuleWins() {
        VersionUpgradeRules rules = new VersionUpgradeRules("1.0",
                VersionUpgradeRule.Action.BLOCK,
                List.of(
                        new VersionUpgradeRule("network.ike.platform",
                                "ike-bom",
                                VersionUpgradeRule.Action.PIN, "5",
                                "pinned for stability"),
                        new VersionUpgradeRule("network.ike.*", "*",
                                VersionUpgradeRule.Action.ALLOW, null, null)));

        VersionUpgradeRule resolved = rules.resolve(
                "network.ike.platform", "ike-bom");
        assertThat(resolved.action()).isEqualTo(
                VersionUpgradeRule.Action.PIN);
        assertThat(resolved.pinnedVersion()).isEqualTo("5");
        assertThat(resolved.reason()).isEqualTo("pinned for stability");
    }

    @Test
    void laterRuleAppliesWhenFirstDoesNotMatch() {
        VersionUpgradeRules rules = new VersionUpgradeRules("1.0",
                VersionUpgradeRule.Action.BLOCK,
                List.of(
                        new VersionUpgradeRule("network.ike.platform",
                                "ike-bom",
                                VersionUpgradeRule.Action.PIN, "5", null),
                        new VersionUpgradeRule("network.ike.*", "*",
                                VersionUpgradeRule.Action.ALLOW, null, null)));

        VersionUpgradeRule resolved = rules.resolve(
                "network.ike.tooling", "ike-build-standards");
        assertThat(resolved.action()).isEqualTo(
                VersionUpgradeRule.Action.ALLOW);
    }

    @Test
    void unmatchedReturnsDefaultAction() {
        VersionUpgradeRules rules = new VersionUpgradeRules("1.0",
                VersionUpgradeRule.Action.BLOCK,
                List.of(new VersionUpgradeRule("network.ike.*", "*",
                        VersionUpgradeRule.Action.ALLOW, null, null)));

        VersionUpgradeRule resolved = rules.resolve(
                "com.example", "thing");
        assertThat(resolved.action()).isEqualTo(
                VersionUpgradeRule.Action.BLOCK);
        assertThat(resolved.reason()).isEqualTo(
                "no rule matched (default-action)");
    }

    @Test
    void allowAllReturnsAllowForEverything() {
        VersionUpgradeRules rules = VersionUpgradeRules.allowAll();

        assertThat(rules.defaultAction()).isEqualTo(
                VersionUpgradeRule.Action.ALLOW);
        assertThat(rules.resolve("anything", "any-art").action())
                .isEqualTo(VersionUpgradeRule.Action.ALLOW);
    }

    @Test
    void canonicalConstructorCopiesRulesDefensively() {
        java.util.ArrayList<VersionUpgradeRule> mutable = new java.util.ArrayList<>();
        mutable.add(new VersionUpgradeRule("a", "b",
                VersionUpgradeRule.Action.ALLOW, null, null));
        VersionUpgradeRules rules = new VersionUpgradeRules("1.0",
                VersionUpgradeRule.Action.BLOCK, mutable);

        mutable.clear();
        assertThat(rules.rules()).hasSize(1);
    }

    @Test
    void nullRulesNormalizedToEmpty() {
        VersionUpgradeRules rules = new VersionUpgradeRules("1.0",
                VersionUpgradeRule.Action.BLOCK, null);
        assertThat(rules.rules()).isEmpty();
    }
}
