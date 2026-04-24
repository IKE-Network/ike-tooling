package network.ike.plugin.scaffold;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScaffoldPlanTest {

    private static ManifestEntry entry(String dest) {
        return new ManifestEntry(
                dest, ScaffoldScope.PROJECT,
                ScaffoldTier.TOOL_OWNED, dest, null, Map.of());
    }

    private static TierAction write(ManifestEntry e) {
        byte[] bytes = "x".getBytes();
        return new TierAction.Write(
                e, Paths.get("/tmp", e.dest()), bytes,
                "sha256:aa", "sha256:aa",
                TierAction.Write.Kind.INSTALL, "install");
    }

    private static TierAction skip(ManifestEntry e) {
        return new TierAction.Skip(
                e, Paths.get("/tmp", e.dest()),
                "user-edited", "--- a\n+++ b\n");
    }

    private static TierAction uptoDate(ManifestEntry e) {
        return new TierAction.UpToDate(
                e, Paths.get("/tmp", e.dest()),
                "sha256:aa", "sha256:aa", "up-to-date");
    }

    @Test
    void nullStandardsVersionRejected() {
        assertThatThrownBy(() ->
                new ScaffoldPlan(null, List.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("manifestStandardsVersion");
    }

    @Test
    void nullEntriesBecomesEmpty() {
        ScaffoldPlan p = new ScaffoldPlan("7", null);
        assertThat(p.entries()).isEmpty();
        assertThat(p.hasWrites()).isFalse();
        assertThat(p.hasSkips()).isFalse();
    }

    @Test
    void hasWritesReflectsWriteActions() {
        ManifestEntry e = entry("mvnw");
        ScaffoldPlan p = new ScaffoldPlan("7", List.of(
                new PlannedEntry(e, write(e), List.of())));
        assertThat(p.hasWrites()).isTrue();
        assertThat(p.hasSkips()).isFalse();
    }

    @Test
    void hasSkipsReflectsSkipActions() {
        ManifestEntry e = entry("mvnw");
        ScaffoldPlan p = new ScaffoldPlan("7", List.of(
                new PlannedEntry(e, skip(e), List.of())));
        assertThat(p.hasWrites()).isFalse();
        assertThat(p.hasSkips()).isTrue();
    }

    @Test
    void uptoDateIsNeitherWriteNorSkip() {
        ManifestEntry e = entry("mvnw");
        ScaffoldPlan p = new ScaffoldPlan("7", List.of(
                new PlannedEntry(e, uptoDate(e), List.of())));
        assertThat(p.hasWrites()).isFalse();
        assertThat(p.hasSkips()).isFalse();
    }

    @Test
    void entriesListIsDefensivelyCopied() {
        ManifestEntry e = entry("mvnw");
        java.util.ArrayList<PlannedEntry> mutable =
                new java.util.ArrayList<>(List.of(
                        new PlannedEntry(e, uptoDate(e), List.of())));
        ScaffoldPlan p = new ScaffoldPlan("7", mutable);
        mutable.clear();
        assertThat(p.entries()).hasSize(1);
    }
}
