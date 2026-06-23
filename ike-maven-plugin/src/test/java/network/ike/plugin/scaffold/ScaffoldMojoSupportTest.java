package network.ike.plugin.scaffold;

import org.apache.maven.api.plugin.Log;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link ScaffoldMojoSupport} — the shared plumbing that
 * backs {@code ike:scaffold-draft}, {@code ike:scaffold-publish}, and
 * {@code ike:scaffold-revert}.
 */
class ScaffoldMojoSupportTest {

    private static ManifestEntry toolOwned(String dest) {
        return new ManifestEntry(
                dest, ScaffoldScope.PROJECT,
                ScaffoldTier.TOOL_OWNED, dest, null, Map.of());
    }

    private static ManifestEntry tracked(String dest) {
        return new ManifestEntry(
                dest, ScaffoldScope.PROJECT,
                ScaffoldTier.TRACKED, dest, null, Map.of());
    }

    // ── loadManifest ────────────────────────────────────────────────

    @Test
    void loadManifest_rejectsNull() {
        assertThatThrownBy(() -> ScaffoldMojoSupport.loadManifest(null))
                .isInstanceOf(ScaffoldException.class)
                .hasMessageContaining("scaffoldDir is required");
    }

    @Test
    void loadManifest_rejectsMissingDirectory(@TempDir Path tmp) {
        Path missing = tmp.resolve("nope");
        assertThatThrownBy(() ->
                ScaffoldMojoSupport.loadManifest(missing))
                .isInstanceOf(ScaffoldException.class)
                .hasMessageContaining("not a directory");
    }

    @Test
    void loadManifest_rejectsMissingManifestFile(@TempDir Path tmp) {
        assertThatThrownBy(() -> ScaffoldMojoSupport.loadManifest(tmp))
                .isInstanceOf(ScaffoldException.class)
                .hasMessageContaining("missing scaffold-manifest.yaml");
    }

    @Test
    void loadManifest_readsValidManifest(@TempDir Path scaffold)
            throws IOException {
        Files.writeString(
                scaffold.resolve(ScaffoldMojoSupport.MANIFEST_FILE),
                """
                        schema: 1
                        standards-version: "7"
                        files:
                          - dest: mvnw
                            scope: project
                            tier: tool-owned
                            source: mvnw
                        """);
        ScaffoldManifest m = ScaffoldMojoSupport.loadManifest(scaffold);
        assertThat(m.standardsVersion()).isEqualTo("7");
        assertThat(m.entries()).hasSize(1);
        assertThat(m.entries().get(0).dest()).isEqualTo("mvnw");
    }

    // ── lockfile paths ──────────────────────────────────────────────

    @Test
    void projectLockfilePath_returnsProjectRelative(@TempDir Path proj) {
        Path p = ScaffoldMojoSupport.projectLockfilePath(proj);
        assertThat(p).isEqualTo(proj.resolve(".ike/scaffold.lock"));
    }

    @Test
    void projectLockfilePath_returnsNullWhenProjectRootNull() {
        assertThat(ScaffoldMojoSupport.projectLockfilePath(null))
                .isNull();
    }

    @Test
    void userLockfilePath_returnsUserRelative(@TempDir Path home) {
        Path p = ScaffoldMojoSupport.userLockfilePath(home);
        assertThat(p).isEqualTo(home.resolve(".ike/scaffold.lock"));
    }

    // ── loadLockfileOrEmpty ─────────────────────────────────────────

    @Test
    void loadLockfileOrEmpty_returnsEmptyWhenPathNull() {
        ScaffoldLockfile lf =
                ScaffoldMojoSupport.loadLockfileOrEmpty(null);
        assertThat(lf.files()).isEmpty();
    }

    @Test
    void loadLockfileOrEmpty_returnsEmptyWhenFileAbsent(
            @TempDir Path tmp) {
        Path missing = tmp.resolve("missing.lock");
        ScaffoldLockfile lf =
                ScaffoldMojoSupport.loadLockfileOrEmpty(missing);
        assertThat(lf.files()).isEmpty();
    }

    @Test
    void loadLockfileOrEmpty_readsExistingLockfile(@TempDir Path tmp)
            throws IOException {
        Path lockPath = tmp.resolve("scaffold.lock");
        Files.writeString(lockPath,
                """
                        schema: 1
                        standards-version: "7"
                        applied: "2026-04-23T12:00:00Z"
                        files:
                          mvnw:
                            tier: tool-owned
                            template-sha: "sha256:aa"
                            applied-sha: "sha256:aa"
                        """);
        ScaffoldLockfile lf =
                ScaffoldMojoSupport.loadLockfileOrEmpty(lockPath);
        assertThat(lf.files()).containsKey("mvnw");
    }

    // ── renderPlanReport ────────────────────────────────────────────

    @Test
    void renderPlanReport_listsActionsPerLine() {
        ManifestEntry e = toolOwned("mvnw");
        byte[] tpl = "body".getBytes();
        String sha = Sha256.of(tpl);
        TierAction.Write install = new TierAction.Write(
                e, Path.of("/tmp/mvnw"), tpl, sha, sha,
                TierAction.Write.Kind.INSTALL, "install");
        ScaffoldPlan plan = new ScaffoldPlan("7", List.of(
                new PlannedEntry(e, install, List.of())));

        String rep = ScaffoldMojoSupport.renderPlanReport(
                plan, ScaffoldScope.PROJECT);

        assertThat(rep).contains("project scope (1 entries)");
        assertThat(rep).contains("[INSTALL]");
        assertThat(rep).contains("mvnw");
    }

    @Test
    void renderPlanReport_appendsActivationHintWhenDeclared() {
        ManifestEntry e = new ManifestEntry(
                "~/.git-hooks/post-commit", ScaffoldScope.USER,
                ScaffoldTier.TOOL_OWNED, "tool-owned/hooks/post-commit",
                null, Map.of(
                        "mode", "private",
                        "activation", "Activate with: chmod +x hook"));
        byte[] tpl = "body".getBytes();
        String sha = Sha256.of(tpl);
        TierAction.Write install = new TierAction.Write(
                e, Path.of("/tmp/post-commit"), tpl, sha, sha,
                TierAction.Write.Kind.INSTALL, "install");
        ScaffoldPlan plan = new ScaffoldPlan("7", List.of(
                new PlannedEntry(e, install, List.of())));

        String rep = ScaffoldMojoSupport.renderPlanReport(
                plan, ScaffoldScope.USER);

        assertThat(rep).contains("[INSTALL]");
        assertThat(rep).contains("↳ Activate with: chmod +x hook");
    }

    @Test
    void renderPlanReport_omitsActivationHintWhenAbsent() {
        ManifestEntry e = toolOwned("mvnw");
        byte[] tpl = "body".getBytes();
        String sha = Sha256.of(tpl);
        TierAction.Write install = new TierAction.Write(
                e, Path.of("/tmp/mvnw"), tpl, sha, sha,
                TierAction.Write.Kind.INSTALL, "install");
        ScaffoldPlan plan = new ScaffoldPlan("7", List.of(
                new PlannedEntry(e, install, List.of())));

        String rep = ScaffoldMojoSupport.renderPlanReport(
                plan, ScaffoldScope.PROJECT);

        assertThat(rep).doesNotContain("↳");
    }

    @Test
    void renderPlanReport_handlesEmptyPlan() {
        ScaffoldPlan empty = new ScaffoldPlan("7", List.of());
        String rep = ScaffoldMojoSupport.renderPlanReport(
                empty, ScaffoldScope.USER);
        assertThat(rep).contains("user scope (0 entries)");
        assertThat(rep).contains("(nothing in this scope)");
    }

    @Test
    void renderPlanReport_rendersSkipWithDiff() {
        ManifestEntry e = tracked(".gitignore");
        TierAction.Skip skip = new TierAction.Skip(
                e, Path.of("/tmp/.gitignore"),
                "user-edited; +1/-0",
                "--- a\n+++ b\n+added line\n");
        ScaffoldPlan plan = new ScaffoldPlan("7", List.of(
                new PlannedEntry(e, skip, List.of())));

        String rep = ScaffoldMojoSupport.renderPlanReport(
                plan, ScaffoldScope.PROJECT);

        assertThat(rep).contains("[SKIP]");
        assertThat(rep).contains(".gitignore");
        assertThat(rep).contains("user-edited");
        assertThat(rep).contains("+added line");
    }

    @Test
    void renderPlanReport_rendersUpToDate() {
        ManifestEntry e = toolOwned("mvnw");
        TierAction.UpToDate u = new TierAction.UpToDate(
                e, Path.of("/tmp/mvnw"),
                "sha256:aa", "sha256:aa", "up-to-date");
        ScaffoldPlan plan = new ScaffoldPlan("7", List.of(
                new PlannedEntry(e, u, List.of())));

        String rep = ScaffoldMojoSupport.renderPlanReport(
                plan, ScaffoldScope.PROJECT);

        assertThat(rep).contains("[OK]");
        assertThat(rep).contains("up-to-date");
    }

    @Test
    void renderPlanReport_rendersUserManagedDistinctly() {
        ManifestEntry e = new ManifestEntry(
                "~/.gitconfig", ScaffoldScope.USER,
                ScaffoldTier.MODEL_MANAGED, null,
                "git-config", Map.of());
        TierAction.UserManaged m = new TierAction.UserManaged(
                e, Path.of("/tmp/.gitconfig"),
                "sha256:aa", "sha256:aa",
                "deferred to user value for [core].hooksPath");
        ScaffoldPlan plan = new ScaffoldPlan("7", List.of(
                new PlannedEntry(e, m, List.of())));

        String rep = ScaffoldMojoSupport.renderPlanReport(
                plan, ScaffoldScope.USER);

        assertThat(rep).contains("[USER]");
        assertThat(rep).doesNotContain("[OK]");
        assertThat(rep).contains("deferred to user value");
        assertThat(rep).contains("[core].hooksPath");
    }

    // ── countActions ────────────────────────────────────────────────

    @Test
    void countActions_tallyEachKind() {
        ManifestEntry e = toolOwned("f");
        byte[] tpl = "b".getBytes();
        String sha = Sha256.of(tpl);
        Path p = Path.of("/tmp/f");
        TierAction.Write inst = new TierAction.Write(
                e, p, tpl, sha, sha,
                TierAction.Write.Kind.INSTALL, "x");
        TierAction.Write upd = new TierAction.Write(
                e, p, tpl, sha, sha,
                TierAction.Write.Kind.UPDATE, "x");
        TierAction.Skip skip = new TierAction.Skip(
                e, p, "edited", "");
        TierAction.UpToDate ok = new TierAction.UpToDate(
                e, p, sha, sha, "ok");
        TierAction.UserManaged user = new TierAction.UserManaged(
                e, p, sha, sha, "deferred");

        ScaffoldPlan plan = new ScaffoldPlan("7", List.of(
                new PlannedEntry(e, inst, List.of()),
                new PlannedEntry(e, inst, List.of()),
                new PlannedEntry(e, upd, List.of()),
                new PlannedEntry(e, skip, List.of()),
                new PlannedEntry(e, ok, List.of()),
                new PlannedEntry(e, user, List.of())));

        ScaffoldMojoSupport.Counts c =
                ScaffoldMojoSupport.countActions(plan);

        assertThat(c.install()).isEqualTo(2);
        assertThat(c.update()).isEqualTo(1);
        assertThat(c.skip()).isEqualTo(1);
        assertThat(c.upToDate()).isEqualTo(1);
        assertThat(c.userManaged()).isEqualTo(1);
        assertThat(c.total()).isEqualTo(6);
        assertThat(c.hasWrites()).isTrue();
        assertThat(c.summary())
                .isEqualTo(
                        "2 install, 1 update, 1 skip, 1 ok, 1 user");
    }

    @Test
    void countActions_zeroWritesReportsFalse() {
        ScaffoldPlan plan = new ScaffoldPlan("7", List.of());
        ScaffoldMojoSupport.Counts c =
                ScaffoldMojoSupport.countActions(plan);
        assertThat(c.hasWrites()).isFalse();
        assertThat(c.total()).isEqualTo(0);
    }

    // ── renderRevertReport ──────────────────────────────────────────

    @Test
    void renderRevertReport_rendersOutcomes() {
        ScaffoldReverter.RevertResult result =
                new ScaffoldReverter.RevertResult(
                        ScaffoldLockfile.empty(),
                        List.of(
                                new ScaffoldReverter.Outcome(
                                        "mvnw",
                                        ScaffoldReverter
                                                .Outcome.Kind.DELETED,
                                        "deleted", true),
                                new ScaffoldReverter.Outcome(
                                        ".gitignore",
                                        ScaffoldReverter
                                                .Outcome.Kind.SKIPPED,
                                        "edited", false),
                                new ScaffoldReverter.Outcome(
                                        "pom.xml",
                                        ScaffoldReverter.Outcome.Kind
                                                .REMOVED_FROM_LOCKFILE,
                                        "already absent", true)));

        String rep = ScaffoldMojoSupport.renderRevertReport(
                result, ScaffoldScope.PROJECT);

        assertThat(rep).contains("project scope (3 entries)");
        assertThat(rep).contains("[DELETED]");
        assertThat(rep).contains("[SKIP]");
        assertThat(rep).contains("[CLEARED]");
        assertThat(rep).contains("mvnw");
        assertThat(rep).contains(".gitignore");
        assertThat(rep).contains("pom.xml");
    }

    @Test
    void renderRevertReport_handlesEmptyResult() {
        ScaffoldReverter.RevertResult result =
                new ScaffoldReverter.RevertResult(
                        ScaffoldLockfile.empty(), List.of());
        String rep = ScaffoldMojoSupport.renderRevertReport(
                result, ScaffoldScope.USER);
        assertThat(rep).contains("user scope (0 entries)");
        assertThat(rep).contains("(nothing to revert)");
    }

    // ── logLines ────────────────────────────────────────────────────

    @Test
    void logLines_splitsOnNewline() {
        RecordingLog log = new RecordingLog();
        ScaffoldMojoSupport.logLines(log, "first\nsecond\nthird");
        assertThat(log.infos).containsExactly("first", "second", "third");
    }

    @Test
    void logLines_singleLineStillWorks() {
        RecordingLog log = new RecordingLog();
        ScaffoldMojoSupport.logLines(log, "only one");
        assertThat(log.infos).containsExactly("only one");
    }

    // ── scopesToProcess ─────────────────────────────────────────────

    @Test
    void scopesToProcess_bothWhenInProject() {
        assertThat(ScaffoldMojoSupport.scopesToProcess(true))
                .containsExactly(
                        ScaffoldScope.USER, ScaffoldScope.PROJECT);
    }

    @Test
    void scopesToProcess_userOnlyWhenFreshMachine() {
        assertThat(ScaffoldMojoSupport.scopesToProcess(false))
                .containsExactly(ScaffoldScope.USER);
    }

    // ── resolveProjectRoot ──────────────────────────────────────────

    @Test
    void resolveProjectRoot_overrideWins(@TempDir Path tmp) {
        // Override always wins, no need for a session.
        assertThat(ScaffoldMojoSupport.resolveProjectRoot(
                tmp.toString(), null))
                .isEqualTo(tmp);
    }

    @Test
    void resolveProjectRoot_blankOverrideAndNullSessionGivesNull() {
        assertThat(ScaffoldMojoSupport.resolveProjectRoot(null, null))
                .isNull();
        assertThat(ScaffoldMojoSupport.resolveProjectRoot("", null))
                .isNull();
        assertThat(ScaffoldMojoSupport.resolveProjectRoot(
                "  ", null)).isNull();
    }

    // ── helpers ─────────────────────────────────────────────────────

    private static final class RecordingLog implements Log {
        final List<String> infos = new ArrayList<>();
        @Override public boolean isDebugEnabled() { return false; }
        @Override public void debug(CharSequence c) {}
        @Override public void debug(CharSequence c, Throwable e) {}
        @Override public void debug(Throwable e) {}
        @Override public void debug(Supplier<String> c) {}
        @Override public void debug(Supplier<String> c, Throwable e) {}
        @Override public boolean isInfoEnabled() { return true; }
        @Override public void info(CharSequence c) {
            infos.add(c.toString());
        }
        @Override public void info(CharSequence c, Throwable e) {
            infos.add(c.toString());
        }
        @Override public void info(Throwable e) {}
        @Override public void info(Supplier<String> c) {
            infos.add(c.get());
        }
        @Override public void info(Supplier<String> c, Throwable e) {
            infos.add(c.get());
        }
        @Override public boolean isWarnEnabled() { return true; }
        @Override public void warn(CharSequence c) {}
        @Override public void warn(CharSequence c, Throwable e) {}
        @Override public void warn(Throwable e) {}
        @Override public void warn(Supplier<String> c) {}
        @Override public void warn(Supplier<String> c, Throwable e) {}
        @Override public boolean isErrorEnabled() { return true; }
        @Override public void error(CharSequence c) {}
        @Override public void error(CharSequence c, Throwable e) {}
        @Override public void error(Throwable e) {}
        @Override public void error(Supplier<String> c) {}
        @Override public void error(Supplier<String> c, Throwable e) {}
    }
}
