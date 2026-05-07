package network.ike.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link MavenVersion} (ike-issues#295).
 *
 * <p>Per {@code feedback_no_semver_assumption}, the validator accepts
 * single-segment monotonic, semver, calendar-based, and
 * branch-qualified versions.
 */
class MavenVersionTest {

    // ── Accept cases ─────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
            // Single-segment monotonic (ike-tooling, ike-platform)
            "1",
            "133",
            "133-SNAPSHOT",
            // Semver-like (tinkar-core, komet)
            "1.0.0",
            "1.0.0-SNAPSHOT",
            "1.127.2-SNAPSHOT",
            "1.127.2-feature-reasoner-SNAPSHOT",
            // Calendar-based
            "20240315-SNAPSHOT",
            "20260507.143000",
            // With + (build metadata)
            "1.0.0+build123",
            // With _ (e.g., milestone names)
            "1.0_M1",
    })
    void of_accepts_real_world_versions(String raw) {
        MavenVersion v = MavenVersion.of(raw);
        assertThat(v.value()).isEqualTo(raw);
    }

    @Test
    void isSnapshot_true_for_dash_SNAPSHOT_suffix() {
        assertThat(MavenVersion.of("1-SNAPSHOT").isSnapshot()).isTrue();
        assertThat(MavenVersion.of("1.0.0-SNAPSHOT").isSnapshot()).isTrue();
        assertThat(MavenVersion.of("1").isSnapshot()).isFalse();
        assertThat(MavenVersion.of("1.0.0").isSnapshot()).isFalse();
    }

    // ── Reject cases ─────────────────────────────────────────────

    @Test
    void of_rejects_null() {
        assertThatThrownBy(() -> MavenVersion.of(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-empty");
    }

    @Test
    void of_rejects_empty() {
        assertThatThrownBy(() -> MavenVersion.of(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-empty");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "1.0 with space",
            "1.0\tspace",
            "<1.0>",            // XML hazard
            "$1",               // shell metachar
            "`evil`",           // backtick
            "1.0;rm",           // shell command separator
            "-leading-dash",
            ".leading-dot",
            "_leading-underscore",
            "naïve",            // non-ASCII
    })
    void of_rejects_invalid_versions(String raw) {
        assertThatThrownBy(() -> MavenVersion.of(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid characters");
    }

    @Test
    void equality_is_value_based() {
        MavenVersion a = MavenVersion.of("1.0.0");
        MavenVersion b = MavenVersion.of("1.0.0");
        MavenVersion c = MavenVersion.of("2.0.0");
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(c);
        assertThat(a).isNotEqualTo("1.0.0");
        assertThat(a).isNotEqualTo(null);
    }
}
