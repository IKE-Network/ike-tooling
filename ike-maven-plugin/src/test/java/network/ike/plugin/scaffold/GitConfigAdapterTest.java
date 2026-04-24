package network.ike.plugin.scaffold;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitConfigAdapterTest {

    private final ModelAdapter adapter = new GitConfigAdapter();
    private final Path dest = Paths.get(
            System.getProperty("user.home"), ".gitconfig");

    private static ManifestEntry entry(Map<String, Object> ensure) {
        Map<String, Object> extras = new LinkedHashMap<>();
        if (ensure != null) {
            extras.put("ensure", ensure);
        }
        return new ManifestEntry(
                "~/.gitconfig", ScaffoldScope.USER,
                ScaffoldTier.MODEL_MANAGED, null,
                GitConfigAdapter.MODEL_NAME, extras);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String str(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    @Test
    void missingFileBecomesInstall() {
        ModelPlanResult r = adapter.plan(
                entry(Map.of(
                        "core", Map.of("autocrlf", "false"))),
                dest, null, null, "7");
        TierAction.Write w = (TierAction.Write) r.action();
        assertThat(w.kind())
                .isEqualTo(TierAction.Write.Kind.INSTALL);
        assertThat(str(w.newContent()))
                .contains("[core]")
                .contains("autocrlf = false");
        assertThat(r.managedElements()).hasSize(1);
    }

    @Test
    void existingSectionGetsKeyAppended() {
        String current = """
                [user]
                \tname = Test User
                \temail = test@example.com
                """;
        ModelPlanResult r = adapter.plan(
                entry(Map.of(
                        "user", Map.of("signingkey", "ABCD"))),
                dest, bytes(current), null, "7");
        TierAction.Write w = (TierAction.Write) r.action();
        String out = str(w.newContent());
        assertThat(out).contains("Test User");
        assertThat(out).contains("signingkey = ABCD");
    }

    @Test
    void existingKeyWithDivergentValueIsUserManaged() {
        String current = """
                [core]
                \tautocrlf = input
                """;
        ModelPlanResult r = adapter.plan(
                entry(Map.of(
                        "core", Map.of("autocrlf", "false"))),
                dest, bytes(current), null, "7");
        // Existing value 'input' must win AND be reported distinctly —
        // this is NOT [OK] up to date; the manifest wanted "false".
        assertThat(r.action())
                .isInstanceOfSatisfying(TierAction.UserManaged.class,
                        m -> assertThat(m.reason())
                                .contains("[core].autocrlf"));
        assertThat(r.managedElements()).hasSize(1);
    }

    @Test
    void existingKeyWithMatchingValueIsUpToDate() {
        String current = """
                [core]
                \tautocrlf = false
                """;
        ModelPlanResult r = adapter.plan(
                entry(Map.of(
                        "core", Map.of("autocrlf", "false"))),
                dest, bytes(current), null, "7");
        assertThat(r.action())
                .isInstanceOf(TierAction.UpToDate.class);
        assertThat(r.managedElements()).hasSize(1);
    }

    @Test
    void multipleOverridesSummarisedInReason() {
        String current = """
                [core]
                \tautocrlf = input
                \thooksPath = /custom/hooks
                """;
        Map<String, String> coreKeys = new LinkedHashMap<>();
        coreKeys.put("autocrlf", "false");
        coreKeys.put("hooksPath", "~/.git-hooks");
        Map<String, Object> ensure = new LinkedHashMap<>();
        ensure.put("core", coreKeys);
        ModelPlanResult r = adapter.plan(
                entry(ensure), dest, bytes(current), null, "7");
        assertThat(r.action())
                .isInstanceOfSatisfying(TierAction.UserManaged.class,
                        m -> assertThat(m.reason())
                                .contains("1 other"));
    }

    @Test
    void brandNewSectionAppended() {
        String current = """
                [core]
                \tautocrlf = false
                """;
        ModelPlanResult r = adapter.plan(
                entry(Map.of(
                        "alias", Map.of("st", "status -sb"))),
                dest, bytes(current), null, "7");
        TierAction.Write w = (TierAction.Write) r.action();
        assertThat(str(w.newContent()))
                .contains("[alias]")
                .contains("st = status -sb");
    }

    @Test
    void reportsModelName() {
        assertThat(adapter.modelName()).isEqualTo("git-config");
    }

    @Test
    void malformedEnsureRejected() {
        ManifestEntry bad = entry(Map.of(
                "core", "not-a-mapping"));
        assertThatThrownBy(() ->
                adapter.plan(bad, dest, null, null, "7"))
                .isInstanceOf(ScaffoldException.class)
                .hasMessageContaining("core");
    }

    @Test
    void preservesComments() {
        String current = """
                # my config
                [core]
                \tautocrlf = false
                ; another comment
                """;
        ModelPlanResult r = adapter.plan(
                entry(Map.of(
                        "alias", Map.of("st", "status"))),
                dest, bytes(current), null, "7");
        TierAction.Write w = (TierAction.Write) r.action();
        String out = str(w.newContent());
        assertThat(out).contains("# my config");
        assertThat(out).contains("; another comment");
    }

    @Test
    void priorStandardsVersionPreserved() {
        String current = """
                [core]
                \tautocrlf = false
                """;
        LockfileEntry prior = LockfileEntry.modelManaged(List.of(
                new ManagedElement(
                        "[core].autocrlf",
                        java.time.Instant.parse(
                                "2026-01-01T00:00:00Z"),
                        "3")));
        ModelPlanResult r = adapter.plan(
                entry(Map.of(
                        "core", Map.of("autocrlf", "false"))),
                dest, bytes(current), prior, "7");
        assertThat(r.managedElements().get(0).standardsVersion())
                .isEqualTo("3");
    }
}
