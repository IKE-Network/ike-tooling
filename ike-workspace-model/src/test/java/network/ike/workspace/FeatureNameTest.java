package network.ike.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link FeatureName}.
 *
 * <p>Covers ike-issues#205 — the syntactic validation rules and the
 * single approved entry point for sibling-clone directory name
 * construction.
 */
class FeatureNameTest {

    // ── Accept cases ─────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
            "reasoner",
            "feature1",
            "abc-def",
            "abc_def",
            "abc.def",
            "X",
            "1",
            "0xCAFE",
            "v2-cleanup",
            "very-long-but-valid-feature-name-with-dashes",
    })
    void of_accepts_valid_names(String raw) {
        FeatureName fn = FeatureName.of(raw);
        assertThat(fn.value()).isEqualTo(raw);
        assertThat(fn.toString()).isEqualTo(raw);
    }

    // ── Reject cases ─────────────────────────────────────────────

    @Test
    void of_rejects_null() {
        assertThatThrownBy(() -> FeatureName.of(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-empty");
    }

    @Test
    void of_rejects_empty() {
        assertThatThrownBy(() -> FeatureName.of(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-empty");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "feature/x",      // path separator (forward)
            "feature\\x",     // path separator (back)
            "abc def",        // whitespace
            "abc\tdef",       // tab
            "*glob",          // shell metachar
            "?glob",
            "[bracket]",
            "\"quoted\"",
            "'quoted'",
            "$dollar",
            "`backtick`",
            "-leading-dash",  // must start with letter or digit
            "_leading-under", // must start with letter or digit
            ".leading-dot",   // must start with letter or digit
            "naïve",          // non-ASCII letter
            "feat$",          // shell metachar at end
    })
    void of_rejects_invalid_names(String raw) {
        assertThatThrownBy(() -> FeatureName.of(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("filesystem-safe");
    }

    // ── siblingDirectoryName ─────────────────────────────────────

    @Test
    void siblingDirectoryName_concatenates_with_dash() {
        FeatureName fn = FeatureName.of("reasoner");
        assertThat(fn.siblingDirectoryName("ike-komet-wsr"))
                .isEqualTo("ike-komet-wsr-reasoner");
    }

    @Test
    void siblingDirectoryName_rejects_null_workspace() {
        FeatureName fn = FeatureName.of("x");
        assertThatThrownBy(() -> fn.siblingDirectoryName(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-empty");
    }

    @Test
    void siblingDirectoryName_rejects_empty_workspace() {
        FeatureName fn = FeatureName.of("x");
        assertThatThrownBy(() -> fn.siblingDirectoryName(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-empty");
    }

    // ── equals / hashCode ────────────────────────────────────────

    @Test
    void equality_is_value_based() {
        FeatureName a = FeatureName.of("foo");
        FeatureName b = FeatureName.of("foo");
        FeatureName c = FeatureName.of("bar");
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(c);
        assertThat(a).isNotEqualTo("foo"); // not a String
        assertThat(a).isNotEqualTo(null);
    }
}
