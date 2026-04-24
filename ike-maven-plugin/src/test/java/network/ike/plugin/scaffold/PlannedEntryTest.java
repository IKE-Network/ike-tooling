package network.ike.plugin.scaffold;

import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlannedEntryTest {

    private static ManifestEntry fileEntry() {
        return new ManifestEntry(
                "mvnw", ScaffoldScope.PROJECT,
                ScaffoldTier.TOOL_OWNED, "mvnw", null, Map.of());
    }

    private static ManifestEntry modelEntry() {
        return new ManifestEntry(
                "~/.m2/settings.xml", ScaffoldScope.USER,
                ScaffoldTier.MODEL_MANAGED, null,
                "maven-settings-4", Map.of());
    }

    private static TierAction dummyAction(ManifestEntry entry) {
        return new TierAction.UpToDate(
                entry, Paths.get("/tmp", entry.dest()),
                "sha256:aa", "sha256:aa", "up-to-date");
    }

    @Test
    void nullManifestRejected() {
        assertThatThrownBy(() -> new PlannedEntry(
                null, dummyAction(fileEntry()), List.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("manifest");
    }

    @Test
    void nullActionRejected() {
        assertThatThrownBy(() -> new PlannedEntry(
                fileEntry(), null, List.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("action");
    }

    @Test
    void nullManagedElementsBecomesEmpty() {
        PlannedEntry pe = new PlannedEntry(
                fileEntry(), dummyAction(fileEntry()), null);
        assertThat(pe.managedElements()).isEmpty();
    }

    @Test
    void managedElementsRejectedForNonModelTier() {
        ManagedElement el = new ManagedElement(
                "/settings/pluginGroups/pluginGroup[text()='x']",
                Instant.parse("2026-01-01T00:00:00Z"), "1");
        assertThatThrownBy(() -> new PlannedEntry(
                fileEntry(), dummyAction(fileEntry()), List.of(el)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("model-managed");
    }

    @Test
    void managedElementsAcceptedForModelTier() {
        ManagedElement el = new ManagedElement(
                "/settings/pluginGroups/pluginGroup[text()='x']",
                Instant.parse("2026-01-01T00:00:00Z"), "1");
        PlannedEntry pe = new PlannedEntry(
                modelEntry(), dummyAction(modelEntry()), List.of(el));
        assertThat(pe.managedElements()).containsExactly(el);
    }

    @Test
    void managedElementsListIsDefensivelyCopied() {
        ManagedElement el = new ManagedElement(
                "/x", Instant.parse("2026-01-01T00:00:00Z"), "1");
        java.util.ArrayList<ManagedElement> mutable =
                new java.util.ArrayList<>(List.of(el));
        PlannedEntry pe = new PlannedEntry(
                modelEntry(), dummyAction(modelEntry()), mutable);
        mutable.clear();
        assertThat(pe.managedElements()).hasSize(1);
    }
}
