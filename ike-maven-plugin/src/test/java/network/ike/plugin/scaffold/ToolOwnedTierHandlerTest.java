package network.ike.plugin.scaffold;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolOwnedTierHandlerTest {

    private final TierHandler handler = new ToolOwnedTierHandler();

    private final ManifestEntry entry = new ManifestEntry(
            "mvnw", ScaffoldScope.PROJECT,
            ScaffoldTier.TOOL_OWNED, "mvnw", null, Map.of());
    private final Path dest = Paths.get("/tmp/mvnw");

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void reportsTier() {
        assertThat(handler.tier())
                .isEqualTo(ScaffoldTier.TOOL_OWNED);
    }

    @Test
    void missingFileBecomesInstall() {
        byte[] tpl = bytes("#!/bin/sh\nmvnw body\n");
        TierAction a = handler.plan(entry, dest, null, tpl, null);
        assertThat(a).isInstanceOfSatisfying(TierAction.Write.class,
                w -> {
                    assertThat(w.kind())
                            .isEqualTo(TierAction.Write.Kind.INSTALL);
                    assertThat(w.newContent()).isEqualTo(tpl);
                    assertThat(w.templateSha()).isEqualTo(Sha256.of(tpl));
                    assertThat(w.appliedSha()).isEqualTo(w.templateSha());
                });
    }

    @Test
    void matchingFileBecomesUpToDate() {
        byte[] tpl = bytes("same\n");
        TierAction a = handler.plan(entry, dest, tpl, tpl,
                LockfileEntry.toolOwned(Sha256.of(tpl)));
        assertThat(a).isInstanceOfSatisfying(TierAction.UpToDate.class,
                u -> {
                    assertThat(u.templateSha()).isEqualTo(Sha256.of(tpl));
                    assertThat(u.appliedSha()).isEqualTo(u.templateSha());
                });
    }

    @Test
    void divergedFileBecomesUpdateNotSkip() {
        byte[] tpl = bytes("new body\n");
        byte[] onDisk = bytes("user edited\n");
        TierAction a = handler.plan(entry, dest, onDisk, tpl,
                LockfileEntry.toolOwned(Sha256.of(bytes("old body\n"))));
        assertThat(a).isInstanceOfSatisfying(TierAction.Write.class,
                w -> {
                    assertThat(w.kind())
                            .isEqualTo(TierAction.Write.Kind.UPDATE);
                    assertThat(w.newContent()).isEqualTo(tpl);
                    assertThat(w.reason())
                            .contains("overwrite");
                });
    }

    @Test
    void matchingPriorAppliedBecomesSimpleRefresh() {
        byte[] tpl = bytes("new body\n");
        byte[] onDisk = bytes("old body\n");
        TierAction a = handler.plan(entry, dest, onDisk, tpl,
                LockfileEntry.toolOwned(Sha256.of(onDisk)));
        assertThat(a).isInstanceOfSatisfying(TierAction.Write.class,
                w -> {
                    assertThat(w.kind())
                            .isEqualTo(TierAction.Write.Kind.UPDATE);
                    assertThat(w.reason()).isEqualTo("refresh");
                });
    }

    @Test
    void nullTemplateRejected() {
        assertThatThrownBy(() ->
                handler.plan(entry, dest, null, null, null))
                .isInstanceOf(ScaffoldException.class)
                .hasMessageContaining("tool-owned");
    }
}
