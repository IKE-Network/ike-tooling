package network.ike.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link SubprojectName} (ike-issues#295).
 */
class SubprojectNameTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "tinkar-core",
            "komet-bom",
            "ike-commonmark-attributes",
            "rocks-kb",
            "lib-1.2",
            "X",
            "0xCAFE",
    })
    void of_accepts_real_world_subproject_names(String raw) {
        SubprojectName n = SubprojectName.of(raw);
        assertThat(n.value()).isEqualTo(raw);
        assertThat(n.toString()).isEqualTo(raw);
    }

    @Test
    void of_rejects_null() {
        assertThatThrownBy(() -> SubprojectName.of(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-empty");
    }

    @Test
    void of_rejects_empty() {
        assertThatThrownBy(() -> SubprojectName.of(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-empty");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "with/slash",
            "with\\backslash",
            "with space",
            "*glob",
            "$dollar",
            "`backtick`",
            "-leading-dash",
            ".leading-dot",
            "_leading-underscore",
            "naïve",        // non-ASCII
            "name@scope",
            "name:tag",
    })
    void of_rejects_invalid_names(String raw) {
        assertThatThrownBy(() -> SubprojectName.of(raw))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("filesystem-safe");
    }

    @Test
    void equality_is_value_based() {
        SubprojectName a = SubprojectName.of("foo");
        SubprojectName b = SubprojectName.of("foo");
        SubprojectName c = SubprojectName.of("bar");
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(c);
        assertThat(a).isNotEqualTo("foo");
        assertThat(a).isNotEqualTo(null);
    }
}
