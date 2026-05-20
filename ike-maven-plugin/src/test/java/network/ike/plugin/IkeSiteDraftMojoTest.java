package network.ike.plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link IkeSiteDraftMojo#latestReleaseTag(File)} — the helper
 * that resolves the LAST RELEASED version of a project from its git tag
 * history, used by {@code site-publish} so the landing-page entry on
 * https://ike.network/ records a version Central actually serves
 * (IKE-Network/ike-issues#465).
 *
 * <p>Pre-#465 the goal stripped {@code -SNAPSHOT} from the working-tree
 * POM, which produced {@code 3} on a workspace that had only released
 * v2. The new resolver queries git for {@code v*} tags ordered by
 * version (not lexical) and returns the latest.
 */
class IkeSiteDraftMojoTest {

    @TempDir
    Path tempDir;

    @Test
    void returns_empty_for_repo_with_no_v_tags() throws Exception {
        File repo = initGitRepo(tempDir, "no-tags");
        addAndCommit(repo, "initial");

        assertThat(IkeSiteDraftMojo.latestReleaseTag(repo))
                .isEmpty();
    }

    @Test
    void returns_latest_tag_with_v_prefix_stripped() throws Exception {
        File repo = initGitRepo(tempDir, "single-tag");
        addAndCommit(repo, "initial");
        gitTag(repo, "v2");

        assertThat(IkeSiteDraftMojo.latestReleaseTag(repo))
                .contains("2");
    }

    @Test
    void picks_highest_by_version_not_lexical_order() throws Exception {
        // v10 > v9 numerically, but git's default `git tag` sort would
        // place v10 between v1 and v2 lexically. for-each-ref with
        // --sort=-v:refname must use the version-aware comparator.
        File repo = initGitRepo(tempDir, "version-sort");
        addAndCommit(repo, "initial");
        gitTag(repo, "v9");
        gitTag(repo, "v10");
        gitTag(repo, "v1");
        gitTag(repo, "v2");

        assertThat(IkeSiteDraftMojo.latestReleaseTag(repo))
                .contains("10");
    }

    @Test
    void ignores_tags_that_do_not_start_with_v() throws Exception {
        // Release-of-record format is `vN`. A stray `release-3` or
        // similar should not be picked up — it's not a versioned tag
        // for this project.
        File repo = initGitRepo(tempDir, "mixed-tags");
        addAndCommit(repo, "initial");
        gitTag(repo, "v1");
        gitTag(repo, "release-9");
        gitTag(repo, "milestone-4");

        assertThat(IkeSiteDraftMojo.latestReleaseTag(repo))
                .contains("1");
    }

    @Test
    void returns_empty_for_non_git_directory() throws Exception {
        Path dir = Files.createDirectory(tempDir.resolve("not-a-repo"));

        assertThat(IkeSiteDraftMojo.latestReleaseTag(dir.toFile()))
                .isEmpty();
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private static File initGitRepo(Path parent, String name) throws Exception {
        Path repo = Files.createDirectory(parent.resolve(name));
        run(repo, "git", "init", "-b", "main");
        run(repo, "git", "config", "user.email", "test@example.org");
        run(repo, "git", "config", "user.name", "test");
        // Disable signing globally in tests — repos may be on hosts
        // with gpg-sign defaults that would fail tagging without a key.
        run(repo, "git", "config", "commit.gpgsign", "false");
        run(repo, "git", "config", "tag.gpgsign", "false");
        return repo.toFile();
    }

    private static void addAndCommit(File repo, String message) throws Exception {
        Path readme = repo.toPath().resolve("README.md");
        Files.writeString(readme,
                "# fixture\n" + message + " @ " + System.nanoTime() + "\n");
        run(repo.toPath(), "git", "add", "README.md");
        run(repo.toPath(), "git", "commit", "-m", message);
    }

    private static void gitTag(File repo, String tag) throws Exception {
        // Bump the tree so each tag points at a distinct commit.
        addAndCommit(repo, "tagging " + tag);
        run(repo.toPath(), "git", "tag", tag);
    }

    private static void run(Path cwd, String... cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(cwd.toFile())
                .redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes());
        int rc = p.waitFor();
        if (rc != 0) {
            throw new RuntimeException(
                    String.join(" ", cmd) + " failed (exit " + rc + "):\n"
                    + output);
        }
    }
}
