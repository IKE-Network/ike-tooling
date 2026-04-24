package network.ike.plugin.scaffold;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LockfileEntryTest {

    @Test
    void toolOwnedEntryHasOnlyTemplateSha() {
        LockfileEntry e = LockfileEntry.toolOwned("sha256:abc");
        assertThat(e.tier()).isEqualTo(ScaffoldTier.TOOL_OWNED);
        assertThat(e.templateSha()).isEqualTo("sha256:abc");
        assertThat(e.appliedSha()).isNull();
        assertThat(e.managedElements()).isEmpty();
    }

    @Test
    void trackedEntryHasBothShas() {
        LockfileEntry e = LockfileEntry.tracked(
                ScaffoldTier.TRACKED, "sha256:t", "sha256:a");
        assertThat(e.tier()).isEqualTo(ScaffoldTier.TRACKED);
        assertThat(e.templateSha()).isEqualTo("sha256:t");
        assertThat(e.appliedSha()).isEqualTo("sha256:a");
    }

    @Test
    void trackedBlockEntryAcceptedViaTrackedFactory() {
        LockfileEntry e = LockfileEntry.tracked(
                ScaffoldTier.TRACKED_BLOCK, "sha256:t", "sha256:a");
        assertThat(e.tier()).isEqualTo(ScaffoldTier.TRACKED_BLOCK);
    }

    @Test
    void trackedFactoryRejectsToolOwned() {
        assertThatThrownBy(() -> LockfileEntry.tracked(
                ScaffoldTier.TOOL_OWNED, "s", "s"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TRACKED");
    }

    @Test
    void modelManagedEntryHoldsManagedElements() {
        ManagedElement el = new ManagedElement(
                "pluginGroups/pluginGroup[.=\"network.ike.tooling\"]",
                Instant.parse("2026-04-23T10:15:00Z"),
                "7");
        LockfileEntry e = LockfileEntry.modelManaged(List.of(el));
        assertThat(e.tier()).isEqualTo(ScaffoldTier.MODEL_MANAGED);
        assertThat(e.templateSha()).isNull();
        assertThat(e.appliedSha()).isNull();
        assertThat(e.managedElements()).containsExactly(el);
    }

    @Test
    void modelManagedEntryRejectsShaFields() {
        assertThatThrownBy(() -> new LockfileEntry(
                ScaffoldTier.MODEL_MANAGED,
                "sha256:t", null, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("templateSha");
    }

    @Test
    void trackedEntryRejectsManagedElements() {
        ManagedElement el = new ManagedElement(
                "x", Instant.now(), "7");
        assertThatThrownBy(() -> new LockfileEntry(
                ScaffoldTier.TRACKED,
                "sha256:t", "sha256:a", List.of(el)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("managedElements");
    }

    @Test
    void trackedEntryRequiresAppliedSha() {
        assertThatThrownBy(() -> new LockfileEntry(
                ScaffoldTier.TRACKED, "sha256:t", null, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("appliedSha");
    }

    @Test
    void managedElementsListIsImmutable() {
        ManagedElement el = new ManagedElement(
                "x", Instant.now(), "7");
        java.util.ArrayList<ManagedElement> src =
                new java.util.ArrayList<>();
        src.add(el);
        LockfileEntry e = LockfileEntry.modelManaged(src);
        src.clear();
        assertThat(e.managedElements()).containsExactly(el);
        assertThatThrownBy(() ->
                e.managedElements().add(el))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
