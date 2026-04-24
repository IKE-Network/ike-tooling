package network.ike.plugin.scaffold;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrackedTierHandlerTest {

    private final TierHandler handler = new TrackedTierHandler();

    private final ManifestEntry entry = new ManifestEntry(
            ".mvn/maven.config", ScaffoldScope.PROJECT,
            ScaffoldTier.TRACKED, ".mvn/maven.config", null, Map.of());
    private final Path dest = Paths.get("/tmp/maven.config");

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void reportsTier() {
        assertThat(handler.tier())
                .isEqualTo(ScaffoldTier.TRACKED);
    }

    @Test
    void missingFileBecomesInstall() {
        byte[] tpl = bytes("-T1C\n");
        TierAction a = handler.plan(entry, dest, null, tpl, null);
        assertThat(a).isInstanceOfSatisfying(TierAction.Write.class,
                w -> {
                    assertThat(w.kind())
                            .isEqualTo(TierAction.Write.Kind.INSTALL);
                    assertThat(w.newContent()).isEqualTo(tpl);
                    assertThat(w.appliedSha())
                            .isEqualTo(w.templateSha());
                });
    }

    @Test
    void matchingFileBecomesUpToDate() {
        byte[] tpl = bytes("-T1C\n");
        TierAction a = handler.plan(entry, dest, tpl, tpl,
                LockfileEntry.tracked(
                        ScaffoldTier.TRACKED,
                        Sha256.of(tpl), Sha256.of(tpl)));
        assertThat(a).isInstanceOf(TierAction.UpToDate.class);
    }

    @Test
    void matchingPriorAppliedBecomesUpdate() {
        byte[] prior = bytes("-T1C\n");
        byte[] tpl = bytes("-T2C\n");
        TierAction a = handler.plan(entry, dest, prior, tpl,
                LockfileEntry.tracked(
                        ScaffoldTier.TRACKED,
                        Sha256.of(prior), Sha256.of(prior)));
        assertThat(a).isInstanceOfSatisfying(TierAction.Write.class,
                w -> {
                    assertThat(w.kind())
                            .isEqualTo(TierAction.Write.Kind.UPDATE);
                    assertThat(w.newContent()).isEqualTo(tpl);
                    assertThat(w.reason()).contains("refresh");
                });
    }

    @Test
    void divergedFromPriorBecomesSkip() {
        byte[] tpl = bytes("-T2C\n");
        byte[] onDisk = bytes("-T1C -U\n");
        TierAction a = handler.plan(entry, dest, onDisk, tpl,
                LockfileEntry.tracked(
                        ScaffoldTier.TRACKED,
                        Sha256.of(bytes("-T1C\n")),
                        Sha256.of(bytes("-T1C\n"))));
        assertThat(a).isInstanceOfSatisfying(TierAction.Skip.class,
                s -> {
                    assertThat(s.reason())
                            .contains("user-edited");
                    assertThat(s.diff()).contains("-").contains("+");
                });
    }

    @Test
    void presentWithoutPriorBecomesSkip() {
        byte[] tpl = bytes("-T2C\n");
        byte[] onDisk = bytes("-T1C\n");
        TierAction a = handler.plan(entry, dest, onDisk, tpl, null);
        assertThat(a).isInstanceOfSatisfying(TierAction.Skip.class,
                s -> assertThat(s.reason())
                        .contains("no prior lockfile entry"));
    }

    @Test
    void nullTemplateRejected() {
        assertThatThrownBy(() ->
                handler.plan(entry, dest, null, null, null))
                .isInstanceOf(ScaffoldException.class)
                .hasMessageContaining("tracked");
    }
}
