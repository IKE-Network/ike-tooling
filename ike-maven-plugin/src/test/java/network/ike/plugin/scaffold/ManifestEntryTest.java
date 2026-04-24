package network.ike.plugin.scaffold;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManifestEntryTest {

    @Test
    void toolOwnedEntryRequiresSource() {
        assertThatThrownBy(() -> new ManifestEntry(
                "mvnw", ScaffoldScope.PROJECT,
                ScaffoldTier.TOOL_OWNED,
                null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source");
    }

    @Test
    void toolOwnedEntryRejectsModel() {
        assertThatThrownBy(() -> new ManifestEntry(
                "mvnw", ScaffoldScope.PROJECT,
                ScaffoldTier.TOOL_OWNED,
                "mvnw", "some-model", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("model");
    }

    @Test
    void modelManagedEntryRequiresModel() {
        assertThatThrownBy(() -> new ManifestEntry(
                "~/.m2/settings.xml", ScaffoldScope.USER,
                ScaffoldTier.MODEL_MANAGED,
                null, null, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("model");
    }

    @Test
    void modelManagedEntryRejectsSource() {
        assertThatThrownBy(() -> new ManifestEntry(
                "~/.m2/settings.xml", ScaffoldScope.USER,
                ScaffoldTier.MODEL_MANAGED,
                "some-source", "maven-settings-4", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source");
    }

    @Test
    void trackedBlockEntryRequiresSource() {
        ManifestEntry e = new ManifestEntry(
                ".gitignore", ScaffoldScope.PROJECT,
                ScaffoldTier.TRACKED_BLOCK,
                ".gitignore.ike-block", null, Map.of(
                        "block-begin", "# BEGIN ike-managed",
                        "block-end", "# END ike-managed"));
        assertThat(e.extras()).containsKey("block-begin");
    }

    @Test
    void extrasMapIsImmutable() {
        Map<String, Object> src = new LinkedHashMap<>();
        src.put("k", "v");
        ManifestEntry e = new ManifestEntry(
                "mvnw", ScaffoldScope.PROJECT,
                ScaffoldTier.TOOL_OWNED,
                "mvnw", null, src);
        src.clear();
        assertThat(e.extras()).containsOnlyKeys("k");
        assertThatThrownBy(() ->
                e.extras().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nullExtrasTreatedAsEmpty() {
        ManifestEntry e = new ManifestEntry(
                "mvnw", ScaffoldScope.PROJECT,
                ScaffoldTier.TOOL_OWNED,
                "mvnw", null, null);
        assertThat(e.extras()).isEmpty();
    }

    @Test
    void blankDestRejected() {
        assertThatThrownBy(() -> new ManifestEntry(
                "   ", ScaffoldScope.PROJECT,
                ScaffoldTier.TOOL_OWNED,
                "mvnw", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dest");
    }
}
