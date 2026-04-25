package network.ike.workspace;

import org.junit.jupiter.api.Test;

import static network.ike.workspace.VersionUpgradeRule.globMatch;
import static org.assertj.core.api.Assertions.assertThat;

class VersionUpgradeRuleTest {

    // ── globMatch ────────────────────────────────────────────────────

    @Test
    void wildcardMatchesAnyString() {
        assertThat(globMatch("*", "anything")).isTrue();
        assertThat(globMatch("*", "")).isTrue();
        assertThat(globMatch("*", "network.ike.platform")).isTrue();
    }

    @Test
    void literalPatternMatchesExact() {
        assertThat(globMatch("ike-bom", "ike-bom")).isTrue();
        assertThat(globMatch("ike-bom", "ike-parent")).isFalse();
        assertThat(globMatch("ike-bom", "ike-bom-extra")).isFalse();
    }

    @Test
    void prefixWildcardMatchesPrefix() {
        assertThat(globMatch("network.ike.*", "network.ike.platform")).isTrue();
        assertThat(globMatch("network.ike.*", "network.ike.tooling")).isTrue();
        assertThat(globMatch("network.ike.*", "network.ike.")).isTrue();
        assertThat(globMatch("network.ike.*", "network.iketooling")).isFalse();
        assertThat(globMatch("network.ike.*", "com.example")).isFalse();
    }

    @Test
    void suffixWildcardMatchesSuffix() {
        assertThat(globMatch("*-plugin", "ike-maven-plugin")).isTrue();
        assertThat(globMatch("*-plugin", "ike-doc-maven-plugin")).isTrue();
        assertThat(globMatch("*-plugin", "ike-bom")).isFalse();
    }

    @Test
    void middleWildcardMatchesAcrossSegments() {
        assertThat(globMatch("ike-*-plugin", "ike-maven-plugin")).isTrue();
        assertThat(globMatch("ike-*-plugin", "ike-plugin")).isFalse();
    }

    @Test
    void dotsInPatternsAreLiteral() {
        // '.' is not a glob meta-char; must match literally.
        assertThat(globMatch("network.ike", "networkXike")).isFalse();
        assertThat(globMatch("network.ike", "network.ike")).isTrue();
    }

    @Test
    void nullsReturnFalse() {
        assertThat(globMatch(null, "x")).isFalse();
        assertThat(globMatch("*", null)).isFalse();
    }

    // ── matches ──────────────────────────────────────────────────────

    @Test
    void rulesMatchesBothPatterns() {
        VersionUpgradeRule rule = new VersionUpgradeRule(
                "network.ike.*", "*",
                VersionUpgradeRule.Action.ALLOW, null, null);
        assertThat(rule.matches("network.ike.platform", "ike-bom")).isTrue();
        assertThat(rule.matches("com.example", "anything")).isFalse();
    }

    @Test
    void specificArtifactPatternIsRespected() {
        VersionUpgradeRule rule = new VersionUpgradeRule(
                "*", "ike-bom",
                VersionUpgradeRule.Action.PIN, "5", null);
        assertThat(rule.matches("network.ike.platform", "ike-bom")).isTrue();
        assertThat(rule.matches("network.ike.platform", "ike-parent")).isFalse();
    }
}
