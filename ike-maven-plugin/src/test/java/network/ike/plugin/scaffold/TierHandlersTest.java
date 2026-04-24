package network.ike.plugin.scaffold;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TierHandlersTest {

    private final TierHandlers handlers = new TierHandlers();

    @Test
    void defaultRegistryHasAllFileBasedTiers() {
        assertThat(handlers.get(ScaffoldTier.TOOL_OWNED))
                .isInstanceOf(ToolOwnedTierHandler.class);
        assertThat(handlers.get(ScaffoldTier.TRACKED))
                .isInstanceOf(TrackedTierHandler.class);
        assertThat(handlers.get(ScaffoldTier.TRACKED_BLOCK))
                .isInstanceOf(TrackedBlockTierHandler.class);
    }

    @Test
    void modelManagedNotRegisteredByDefault() {
        assertThat(handlers.get(ScaffoldTier.MODEL_MANAGED)).isNull();
    }

    @Test
    void requireThrowsForModelManaged() {
        assertThatThrownBy(() ->
                handlers.require(ScaffoldTier.MODEL_MANAGED))
                .isInstanceOf(ScaffoldException.class)
                .hasMessageContaining("model-managed");
    }

    @Test
    void duplicateHandlerRejected() {
        assertThatThrownBy(() -> new TierHandlers(
                new ToolOwnedTierHandler(),
                new ToolOwnedTierHandler()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
    }
}
