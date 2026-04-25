package network.ike.workspace;

import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VersionUpgradeRulesReaderTest {

    // ── Header ───────────────────────────────────────────────────────

    @Test
    void parsesEmptyHeader() {
        VersionUpgradeRules rules = VersionUpgradeRulesReader.read(
                new StringReader("""
                        schema-version: "1.0"
                        default-action: block
                        rules: []
                        """));
        assertThat(rules.schemaVersion()).isEqualTo("1.0");
        assertThat(rules.defaultAction()).isEqualTo(
                VersionUpgradeRule.Action.BLOCK);
        assertThat(rules.rules()).isEmpty();
    }

    @Test
    void defaultActionDefaultsToBlock() {
        VersionUpgradeRules rules = VersionUpgradeRulesReader.read(
                new StringReader("""
                        schema-version: "1.0"
                        rules: []
                        """));
        assertThat(rules.defaultAction()).isEqualTo(
                VersionUpgradeRule.Action.BLOCK);
    }

    @Test
    void emptyRulesetRejected() {
        assertThatThrownBy(() ->
                VersionUpgradeRulesReader.read(new StringReader("")))
                .isInstanceOf(VersionUpgradeRulesException.class)
                .hasMessageContaining("Empty");
    }

    // ── Rule list ────────────────────────────────────────────────────

    @Test
    void parsesAllowRule() {
        VersionUpgradeRules rules = VersionUpgradeRulesReader.read(
                new StringReader("""
                        rules:
                          - match: "network.ike.*:*"
                            action: allow
                        """));
        assertThat(rules.rules()).hasSize(1);
        VersionUpgradeRule rule = rules.rules().get(0);
        assertThat(rule.groupIdPattern()).isEqualTo("network.ike.*");
        assertThat(rule.artifactIdPattern()).isEqualTo("*");
        assertThat(rule.action()).isEqualTo(
                VersionUpgradeRule.Action.ALLOW);
    }

    @Test
    void parsesPinRule() {
        VersionUpgradeRules rules = VersionUpgradeRulesReader.read(
                new StringReader("""
                        rules:
                          - match: "com.example:frozen-lib"
                            action: pin
                            version: "1.2.3"
                            reason: "deliberately held back"
                        """));
        VersionUpgradeRule rule = rules.rules().get(0);
        assertThat(rule.action()).isEqualTo(VersionUpgradeRule.Action.PIN);
        assertThat(rule.pinnedVersion()).isEqualTo("1.2.3");
        assertThat(rule.reason()).isEqualTo("deliberately held back");
    }

    @Test
    void parsesBlockRule() {
        VersionUpgradeRules rules = VersionUpgradeRulesReader.read(
                new StringReader("""
                        rules:
                          - match: "com.example:broken-lib"
                            action: block
                            reason: "fails our integration tests"
                        """));
        VersionUpgradeRule rule = rules.rules().get(0);
        assertThat(rule.action()).isEqualTo(
                VersionUpgradeRule.Action.BLOCK);
        assertThat(rule.reason()).isEqualTo(
                "fails our integration tests");
    }

    @Test
    void bareGroupIdMatchSplitsToWildcardArtifact() {
        VersionUpgradeRules rules = VersionUpgradeRulesReader.read(
                new StringReader("""
                        rules:
                          - match: "network.ike.platform"
                            action: allow
                        """));
        VersionUpgradeRule rule = rules.rules().get(0);
        assertThat(rule.groupIdPattern()).isEqualTo("network.ike.platform");
        assertThat(rule.artifactIdPattern()).isEqualTo("*");
    }

    @Test
    void rulesPreserveDeclarationOrder() {
        VersionUpgradeRules rules = VersionUpgradeRulesReader.read(
                new StringReader("""
                        rules:
                          - match: "network.ike.platform:ike-bom"
                            action: pin
                            version: "5"
                          - match: "network.ike.*:*"
                            action: allow
                        """));
        assertThat(rules.rules()).hasSize(2);
        assertThat(rules.rules().get(0).action()).isEqualTo(
                VersionUpgradeRule.Action.PIN);
        assertThat(rules.rules().get(1).action()).isEqualTo(
                VersionUpgradeRule.Action.ALLOW);
    }

    // ── Validation errors ────────────────────────────────────────────

    @Test
    void rejectsUnknownAction() {
        assertThatThrownBy(() -> VersionUpgradeRulesReader.read(
                new StringReader("""
                        rules:
                          - match: "*:*"
                            action: maybe
                        """)))
                .isInstanceOf(VersionUpgradeRulesException.class)
                .hasMessageContaining("maybe");
    }

    @Test
    void rejectsPinWithoutVersion() {
        assertThatThrownBy(() -> VersionUpgradeRulesReader.read(
                new StringReader("""
                        rules:
                          - match: "*:*"
                            action: pin
                        """)))
                .isInstanceOf(VersionUpgradeRulesException.class)
                .hasMessageContaining("requires");
    }

    @Test
    void rejectsTooManyColons() {
        assertThatThrownBy(() -> VersionUpgradeRulesReader.read(
                new StringReader("""
                        rules:
                          - match: "g:a:v"
                            action: allow
                        """)))
                .isInstanceOf(VersionUpgradeRulesException.class)
                .hasMessageContaining("more than one");
    }

    @Test
    void rejectsEmptyHalfOfMatch() {
        assertThatThrownBy(() -> VersionUpgradeRulesReader.read(
                new StringReader("""
                        rules:
                          - match: ":artifact"
                            action: allow
                        """)))
                .isInstanceOf(VersionUpgradeRulesException.class)
                .hasMessageContaining("non-empty");
    }

    @Test
    void rejectsRuleMissingMatch() {
        assertThatThrownBy(() -> VersionUpgradeRulesReader.read(
                new StringReader("""
                        rules:
                          - action: allow
                        """)))
                .isInstanceOf(VersionUpgradeRulesException.class)
                .hasMessageContaining("match");
    }

    @Test
    void rejectsRuleMissingAction() {
        assertThatThrownBy(() -> VersionUpgradeRulesReader.read(
                new StringReader("""
                        rules:
                          - match: "*:*"
                        """)))
                .isInstanceOf(VersionUpgradeRulesException.class)
                .hasMessageContaining("action");
    }

    // ── End-to-end resolve ───────────────────────────────────────────

    @Test
    void parsedRulesetResolvesCorrectly() {
        VersionUpgradeRules rules = VersionUpgradeRulesReader.read(
                new StringReader("""
                        default-action: block
                        rules:
                          - match: "network.ike.platform:ike-bom"
                            action: pin
                            version: "5"
                          - match: "network.ike.*:*"
                            action: allow
                        """));

        assertThat(rules.resolve("network.ike.platform", "ike-bom").action())
                .isEqualTo(VersionUpgradeRule.Action.PIN);
        assertThat(rules.resolve("network.ike.tooling", "anything").action())
                .isEqualTo(VersionUpgradeRule.Action.ALLOW);
        assertThat(rules.resolve("com.foo", "bar").action())
                .isEqualTo(VersionUpgradeRule.Action.BLOCK);
    }
}
