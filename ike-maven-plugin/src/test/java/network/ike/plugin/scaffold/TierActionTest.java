package network.ike.plugin.scaffold;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TierActionTest {

    private static final Path DEST = Paths.get("/tmp/x");
    private static final ManifestEntry ENTRY = new ManifestEntry(
            "mvnw", ScaffoldScope.PROJECT,
            ScaffoldTier.TOOL_OWNED, "mvnw", null, Map.of());

    @Test
    void writeDefensivelyCopiesBytesIn() {
        byte[] src = new byte[] {1, 2, 3};
        TierAction.Write w = new TierAction.Write(
                ENTRY, DEST, src,
                Sha256.of(src), Sha256.of(src),
                TierAction.Write.Kind.INSTALL, "install");
        src[0] = 99;
        assertThat(w.newContent()).containsExactly(1, 2, 3);
    }

    @Test
    void writeDefensivelyCopiesBytesOut() {
        byte[] src = new byte[] {1, 2, 3};
        TierAction.Write w = new TierAction.Write(
                ENTRY, DEST, src,
                Sha256.of(src), Sha256.of(src),
                TierAction.Write.Kind.UPDATE, "update");
        byte[] got = w.newContent();
        got[0] = 99;
        assertThat(w.newContent()).containsExactly(1, 2, 3);
    }

    @Test
    void writeRejectsNulls() {
        assertThatThrownBy(() -> new TierAction.Write(
                null, DEST, new byte[0], "x", "y",
                TierAction.Write.Kind.INSTALL, "r"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TierAction.Write(
                ENTRY, null, new byte[0], "x", "y",
                TierAction.Write.Kind.INSTALL, "r"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TierAction.Write(
                ENTRY, DEST, null, "x", "y",
                TierAction.Write.Kind.INSTALL, "r"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void skipNullDiffBecomesEmpty() {
        TierAction.Skip s = new TierAction.Skip(
                ENTRY, DEST, "diverged", null);
        assertThat(s.diff()).isEmpty();
    }

    @Test
    void upToDateHoldsBothHashes() {
        TierAction.UpToDate u = new TierAction.UpToDate(
                ENTRY, DEST, "sha256:tpl", "sha256:applied",
                "up to date");
        assertThat(u.templateSha()).isEqualTo("sha256:tpl");
        assertThat(u.appliedSha()).isEqualTo("sha256:applied");
    }

    @Test
    void actionsAreSealed() {
        // pattern-match exhaustiveness sanity
        TierAction a = new TierAction.UpToDate(
                ENTRY, DEST, "sha256:t", "sha256:a", "ok");
        String label = switch (a) {
            case TierAction.Write w -> "write";
            case TierAction.Skip s -> "skip";
            case TierAction.UpToDate u -> "uptodate";
            case TierAction.UserManaged m -> "usermanaged";
        };
        assertThat(label).isEqualTo("uptodate");
    }

    @Test
    void userManagedHoldsBothHashes() {
        TierAction.UserManaged m = new TierAction.UserManaged(
                ENTRY, DEST, "sha256:tpl", "sha256:applied",
                "deferred to user value for [core].hooksPath");
        assertThat(m.templateSha()).isEqualTo("sha256:tpl");
        assertThat(m.appliedSha()).isEqualTo("sha256:applied");
        assertThat(m.reason()).contains("deferred to user value");
    }

    @Test
    void userManagedRejectsNulls() {
        assertThatThrownBy(() -> new TierAction.UserManaged(
                null, DEST, "t", "a", "r"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TierAction.UserManaged(
                ENTRY, null, "t", "a", "r"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TierAction.UserManaged(
                ENTRY, DEST, null, "a", "r"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TierAction.UserManaged(
                ENTRY, DEST, "t", null, "r"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TierAction.UserManaged(
                ENTRY, DEST, "t", "a", null))
                .isInstanceOf(NullPointerException.class);
    }
}
