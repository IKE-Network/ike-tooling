package network.ike.plugin.scaffold;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScaffoldTierTest {

    @Test
    void eachTierRoundTripsThroughManifestValue() {
        for (ScaffoldTier tier : ScaffoldTier.values()) {
            assertThat(ScaffoldTier.fromManifestValue(tier.manifestValue()))
                    .isEqualTo(tier);
        }
    }

    @Test
    void manifestValueUsesKebabCase() {
        assertThat(ScaffoldTier.TOOL_OWNED.manifestValue())
                .isEqualTo("tool-owned");
        assertThat(ScaffoldTier.TRACKED_BLOCK.manifestValue())
                .isEqualTo("tracked-block");
        assertThat(ScaffoldTier.MODEL_MANAGED.manifestValue())
                .isEqualTo("model-managed");
    }

    @Test
    void fromManifestValueIsCaseInsensitiveAndTrims() {
        assertThat(ScaffoldTier.fromManifestValue("  Tracked  "))
                .isEqualTo(ScaffoldTier.TRACKED);
        assertThat(ScaffoldTier.fromManifestValue("MODEL-MANAGED"))
                .isEqualTo(ScaffoldTier.MODEL_MANAGED);
    }

    @Test
    void fromManifestValueRejectsUnknownSpelling() {
        assertThatThrownBy(() ->
                ScaffoldTier.fromManifestValue("user-owned"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("user-owned")
                .hasMessageContaining("tool-owned")
                .hasMessageContaining("tracked-block");
    }

    @Test
    void fromManifestValueRejectsNull() {
        assertThatThrownBy(() ->
                ScaffoldTier.fromManifestValue(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null");
    }
}
