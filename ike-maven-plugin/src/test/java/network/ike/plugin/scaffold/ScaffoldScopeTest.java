package network.ike.plugin.scaffold;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScaffoldScopeTest {

    @Test
    void eachScopeRoundTrips() {
        for (ScaffoldScope s : ScaffoldScope.values()) {
            assertThat(ScaffoldScope.fromManifestValue(s.manifestValue()))
                    .isEqualTo(s);
        }
    }

    @Test
    void manifestValuesAreLowerCase() {
        assertThat(ScaffoldScope.PROJECT.manifestValue())
                .isEqualTo("project");
        assertThat(ScaffoldScope.USER.manifestValue())
                .isEqualTo("user");
    }

    @Test
    void parseIsCaseInsensitive() {
        assertThat(ScaffoldScope.fromManifestValue("PROJECT"))
                .isEqualTo(ScaffoldScope.PROJECT);
        assertThat(ScaffoldScope.fromManifestValue("  User  "))
                .isEqualTo(ScaffoldScope.USER);
    }

    @Test
    void rejectsUnknownSpelling() {
        assertThatThrownBy(() ->
                ScaffoldScope.fromManifestValue("machine"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("machine");
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() ->
                ScaffoldScope.fromManifestValue(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
