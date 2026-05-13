package network.ike.workspace;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link VersionUpgradeNoise} — the noise / informational
 * classification predicates used by the plan writer and report
 * builder. ike-issues#384.
 */
class VersionUpgradeNoiseTest {

    // ── isPureNoise: from==to + default-action blocked = pure noise ──

    @Test
    void blockedByDefaultActionWithSameVersion_isPureNoise() {
        assertThat(VersionUpgradeNoise.isPureNoise(
                VersionUpgradeStatus.BLOCKED, "13.0.0", "13.0.0",
                "no rule matched (default-action)"))
                .isTrue();
    }

    @Test
    void readyWithSameVersion_isPureNoise() {
        // Defensive: status=READY with from==to is meaningless; treat
        // as noise.
        assertThat(VersionUpgradeNoise.isPureNoise(
                VersionUpgradeStatus.READY, "1.0", "1.0", null))
                .isTrue();
    }

    @Test
    void readyWithDifferentVersions_isNotNoise() {
        assertThat(VersionUpgradeNoise.isPureNoise(
                VersionUpgradeStatus.READY, "1.0", "2.0", null))
                .isFalse();
    }

    @Test
    void blockedWithDifferentVersions_isNotNoise() {
        // Newer version exists, ruleset blocks — actionable.
        assertThat(VersionUpgradeNoise.isPureNoise(
                VersionUpgradeStatus.BLOCKED, "1.0", "2.0",
                "no rule matched (default-action)"))
                .isFalse();
    }

    @Test
    void blockedWithSameVersionButConflictReason_isNotNoise() {
        // from==to but reason is meaningful (conflict). Goes to
        // Warnings, not silently dropped.
        assertThat(VersionUpgradeNoise.isPureNoise(
                VersionUpgradeStatus.BLOCKED, "6.0.0", "6.0.0",
                "consumers disagree on the upgrade target"))
                .isFalse();
    }

    @Test
    void pendingUpstreamWithSameVersion_isNotNoise() {
        // Pending upstream is its own category, not noise.
        assertThat(VersionUpgradeNoise.isPureNoise(
                VersionUpgradeStatus.PENDING_UPSTREAM, "5", "5",
                "awaiting ike-tooling release"))
                .isFalse();
    }

    // ── isInformationalSameVersion: from==to + non-default reason ──

    @Test
    void blockedWithSameVersionAndConflict_isInformational() {
        assertThat(VersionUpgradeNoise.isInformationalSameVersion(
                VersionUpgradeStatus.BLOCKED, "6.0.0", "6.0.0",
                "consumers disagree on the upgrade target"))
                .isTrue();
    }

    @Test
    void blockedByDefaultActionWithSameVersion_isNotInformational() {
        // Default-action with from==to is noise, not informational.
        assertThat(VersionUpgradeNoise.isInformationalSameVersion(
                VersionUpgradeStatus.BLOCKED, "13.0.0", "13.0.0",
                "no rule matched (default-action)"))
                .isFalse();
    }

    @Test
    void blockedWithSameVersionAndNullReason_isNotInformational() {
        // Reason is null → no info to surface as a warning.
        assertThat(VersionUpgradeNoise.isInformationalSameVersion(
                VersionUpgradeStatus.BLOCKED, "1.0", "1.0", null))
                .isFalse();
    }

    @Test
    void blockedWithDifferentVersions_isNotInformational() {
        // from!=to → handled by the regular Ready/Blocked sections.
        assertThat(VersionUpgradeNoise.isInformationalSameVersion(
                VersionUpgradeStatus.BLOCKED, "1.0", "2.0",
                "consumers disagree on the upgrade target"))
                .isFalse();
    }

    @Test
    void readyWithSameVersion_isNotInformational() {
        // status=READY → not a warning.
        assertThat(VersionUpgradeNoise.isInformationalSameVersion(
                VersionUpgradeStatus.READY, "1.0", "1.0",
                "irrelevant"))
                .isFalse();
    }

    // ── Record overloads ────────────────────────────────────────────

    @Test
    void recordOverloads_delegateToCanonicalForm() {
        ParentVersionUpgrade noiseParent = new ParentVersionUpgrade(
                "g", "a", "1.0", "1.0",
                VersionUpgradeStatus.BLOCKED,
                "no rule matched (default-action)");
        assertThat(VersionUpgradeNoise.isPureNoise(noiseParent)).isTrue();
        assertThat(VersionUpgradeNoise.isInformationalSameVersion(noiseParent))
                .isFalse();

        PropertyVersionUpgrade warningProp = new PropertyVersionUpgrade(
                "junit-platform.version", "6.0.0", "6.0.0",
                VersionUpgradeStatus.BLOCKED,
                "consumers disagree on the upgrade target");
        assertThat(VersionUpgradeNoise.isPureNoise(warningProp)).isFalse();
        assertThat(VersionUpgradeNoise.isInformationalSameVersion(warningProp))
                .isTrue();

        LiteralVersionUpgrade readyLit = new LiteralVersionUpgrade(
                "g", "a", "dependency", "1.0", "2.0",
                VersionUpgradeStatus.READY, null);
        assertThat(VersionUpgradeNoise.isPureNoise(readyLit)).isFalse();
        assertThat(VersionUpgradeNoise.isInformationalSameVersion(readyLit))
                .isFalse();
    }

    // ── Null handling ───────────────────────────────────────────────

    @Test
    void bothVersionsNull_treatedAsSame() {
        // Defensive: null==null is "same version".
        assertThat(VersionUpgradeNoise.isPureNoise(
                VersionUpgradeStatus.BLOCKED, null, null,
                "no rule matched (default-action)"))
                .isTrue();
    }

    @Test
    void oneVersionNull_treatedAsDifferent() {
        assertThat(VersionUpgradeNoise.isPureNoise(
                VersionUpgradeStatus.BLOCKED, "1.0", null,
                "no rule matched (default-action)"))
                .isFalse();
    }
}
