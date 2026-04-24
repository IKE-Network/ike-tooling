package network.ike.plugin.scaffold;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelAdaptersTest {

    private final ModelAdapters adapters = new ModelAdapters();

    @Test
    void defaultRegistryHasAllBuiltInModels() {
        assertThat(adapters.get(MavenSettingsAdapter.MODEL_NAME))
                .isInstanceOf(MavenSettingsAdapter.class);
        assertThat(adapters.get(GitConfigAdapter.MODEL_NAME))
                .isInstanceOf(GitConfigAdapter.class);
        assertThat(adapters.get(PomModelAdapter.MODEL_NAME))
                .isInstanceOf(PomModelAdapter.class);
    }

    @Test
    void unknownModelReturnsNull() {
        assertThat(adapters.get("no-such-model")).isNull();
    }

    @Test
    void requireThrowsForUnknownModel() {
        assertThatThrownBy(() -> adapters.require("no-such-model"))
                .isInstanceOf(ScaffoldException.class)
                .hasMessageContaining("no-such-model");
    }

    @Test
    void duplicateAdapterRejected() {
        assertThatThrownBy(() -> new ModelAdapters(
                new MavenSettingsAdapter(),
                new MavenSettingsAdapter()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
    }
}
