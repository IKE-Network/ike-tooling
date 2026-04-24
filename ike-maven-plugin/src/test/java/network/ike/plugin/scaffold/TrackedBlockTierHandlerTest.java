package network.ike.plugin.scaffold;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrackedBlockTierHandlerTest {

    private final TierHandler handler = new TrackedBlockTierHandler();

    private static final String BEGIN = "# BEGIN ike-managed";
    private static final String END = "# END ike-managed";

    private final ManifestEntry entry = entry(Map.of(
            "block-begin", BEGIN,
            "block-end", END));

    private final Path dest = Paths.get("/tmp/.gitignore");

    private static ManifestEntry entry(Map<String, Object> extras) {
        Map<String, Object> e = new LinkedHashMap<>(extras);
        return new ManifestEntry(
                ".gitignore", ScaffoldScope.PROJECT,
                ScaffoldTier.TRACKED_BLOCK,
                ".gitignore.ike-block", null, e);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String str(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Test
    void reportsTier() {
        assertThat(handler.tier())
                .isEqualTo(ScaffoldTier.TRACKED_BLOCK);
    }

    @Test
    void missingFileCreatesFileWithBlock() {
        byte[] tpl = bytes(".idea/\n.DS_Store\n");
        TierAction a = handler.plan(entry, dest, null, tpl, null);
        TierAction.Write w = (TierAction.Write) a;
        assertThat(w.kind())
                .isEqualTo(TierAction.Write.Kind.INSTALL);
        String produced = str(w.newContent());
        assertThat(produced).isEqualTo(
                BEGIN + "\n.idea/\n.DS_Store\n" + END + "\n");
        assertThat(w.appliedSha())
                .isEqualTo(Sha256.of(".idea/\n.DS_Store\n"));
    }

    @Test
    void fileWithoutBlockGetsBlockAppended() {
        byte[] current = bytes("target/\n*.class\n");
        byte[] tpl = bytes(".idea/\n");
        TierAction a = handler.plan(entry, dest, current, tpl, null);
        TierAction.Write w = (TierAction.Write) a;
        assertThat(w.kind())
                .isEqualTo(TierAction.Write.Kind.INSTALL);
        assertThat(str(w.newContent())).isEqualTo(
                "target/\n*.class\n"
                        + BEGIN + "\n.idea/\n" + END + "\n");
    }

    @Test
    void fileWithoutTrailingNewlineGetsOneBeforeBlock() {
        byte[] current = bytes("target/");
        byte[] tpl = bytes(".idea/\n");
        TierAction a = handler.plan(entry, dest, current, tpl, null);
        TierAction.Write w = (TierAction.Write) a;
        assertThat(str(w.newContent())).isEqualTo(
                "target/\n"
                        + BEGIN + "\n.idea/\n" + END + "\n");
    }

    @Test
    void matchingBlockBecomesUpToDate() {
        byte[] tpl = bytes(".idea/\n.DS_Store\n");
        byte[] current = bytes(
                "target/\n"
                        + BEGIN + "\n"
                        + ".idea/\n.DS_Store\n"
                        + END + "\n"
                        + "tmp/\n");
        TierAction a = handler.plan(entry, dest, current, tpl,
                LockfileEntry.tracked(
                        ScaffoldTier.TRACKED_BLOCK,
                        Sha256.of(".idea/\n.DS_Store\n"),
                        Sha256.of(".idea/\n.DS_Store\n")));
        assertThat(a).isInstanceOf(TierAction.UpToDate.class);
    }

    @Test
    void blockMatchingPriorBecomesRefresh() {
        byte[] tpl = bytes(".idea/\n.DS_Store\n.vscode/\n");
        String priorBlock = ".idea/\n.DS_Store\n";
        byte[] current = bytes(
                "target/\n"
                        + BEGIN + "\n"
                        + priorBlock
                        + END + "\n"
                        + "tmp/\n");
        TierAction a = handler.plan(entry, dest, current, tpl,
                LockfileEntry.tracked(
                        ScaffoldTier.TRACKED_BLOCK,
                        Sha256.of(priorBlock),
                        Sha256.of(priorBlock)));
        TierAction.Write w = (TierAction.Write) a;
        assertThat(w.kind())
                .isEqualTo(TierAction.Write.Kind.UPDATE);
        assertThat(str(w.newContent())).isEqualTo(
                "target/\n"
                        + BEGIN + "\n"
                        + ".idea/\n.DS_Store\n.vscode/\n"
                        + END + "\n"
                        + "tmp/\n");
        assertThat(w.reason()).contains("refresh block");
    }

    @Test
    void userEditedBlockBecomesSkip() {
        byte[] tpl = bytes(".idea/\n.DS_Store\n");
        byte[] current = bytes(
                "target/\n"
                        + BEGIN + "\n"
                        + ".idea/\nMY_OWN_FILE\n"
                        + END + "\n");
        TierAction a = handler.plan(entry, dest, current, tpl,
                LockfileEntry.tracked(
                        ScaffoldTier.TRACKED_BLOCK,
                        Sha256.of(".idea/\n"),
                        Sha256.of(".idea/\n")));
        TierAction.Skip s = (TierAction.Skip) a;
        assertThat(s.reason()).contains("user-edited block");
        assertThat(s.diff()).contains("+.DS_Store\n");
    }

    @Test
    void blockPresentWithoutPriorBecomesSkip() {
        byte[] tpl = bytes(".idea/\n");
        byte[] current = bytes(
                BEGIN + "\n.idea/\nother/\n" + END + "\n");
        TierAction a = handler.plan(entry, dest, current, tpl, null);
        assertThat(a).isInstanceOfSatisfying(TierAction.Skip.class,
                s -> assertThat(s.reason())
                        .contains("no prior lockfile entry"));
    }

    @Test
    void missingEndMarkerThrows() {
        byte[] tpl = bytes(".idea/\n");
        byte[] current = bytes(BEGIN + "\n.idea/\n");
        assertThatThrownBy(() ->
                handler.plan(entry, dest, current, tpl, null))
                .isInstanceOf(ScaffoldException.class)
                .hasMessageContaining(END);
    }

    @Test
    void multipleBlocksThrow() {
        byte[] tpl = bytes(".idea/\n");
        byte[] current = bytes(
                BEGIN + "\n.idea/\n" + END + "\n"
                        + BEGIN + "\nother/\n" + END + "\n");
        assertThatThrownBy(() ->
                handler.plan(entry, dest, current, tpl, null))
                .isInstanceOf(ScaffoldException.class)
                .hasMessageContaining("multiple");
    }

    @Test
    void missingBeginMarkerExtraThrows() {
        ManifestEntry bad = entry(Map.of("block-end", END));
        byte[] tpl = bytes("x\n");
        assertThatThrownBy(() ->
                handler.plan(bad, dest, null, tpl, null))
                .isInstanceOf(ScaffoldException.class)
                .hasMessageContaining("block-begin");
    }
}
