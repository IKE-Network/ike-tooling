package network.ike.plugin;

import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link ReleaseSupport#updateLatestSymlink} (ike-issues#303).
 *
 * <p>Two layers exercised:
 * <ul>
 *   <li>Pure path arithmetic ({@code parentDir} + {@code leafName})
 *       on Unix-style absolute paths under {@code /srv/ike-site/}.</li>
 *   <li>End-to-end command shape — supplying a fake SSH prefix that
 *       captures the issued command via {@code echo} so the test
 *       asserts the {@code cd <parent> && ln -snf <leaf> latest}
 *       form without needing a real SSH server.</li>
 * </ul>
 */
class UpdateLatestSymlinkTest {

    @TempDir
    Path tempDir;

    private final Log log = new TestLog();

    // ── Path arithmetic ──────────────────────────────────────────

    @Test
    void parentDir_strips_last_segment() {
        assertThat(ReleaseSupport.parentDir(
                "/srv/ike-site/ike-platform/17"))
                .isEqualTo("/srv/ike-site/ike-platform");
    }

    @Test
    void parentDir_returns_null_at_or_above_site_base() {
        // /srv/ike-site has no SITE_DISK_BASE-prefixed parent.
        assertThat(ReleaseSupport.parentDir("/srv/ike-site"))
                .isNull();
        // /srv has no slash beyond the root → null.
        assertThat(ReleaseSupport.parentDir("/srv"))
                .isNull();
    }

    @Test
    void leafName_returns_basename() {
        assertThat(ReleaseSupport.leafName(
                "/srv/ike-site/ike-platform/17"))
                .isEqualTo("17");
    }

    @Test
    void leafName_tolerates_trailing_slash() {
        assertThat(ReleaseSupport.leafName(
                "/srv/ike-site/ike-platform/17/"))
                .isEqualTo("17");
    }

    @Test
    void leafName_handles_branch_qualified_versions() {
        assertThat(ReleaseSupport.leafName(
                "/srv/ike-site/ike-platform/17-feature-x-SNAPSHOT"))
                .isEqualTo("17-feature-x-SNAPSHOT");
    }

    // ── Command shape ────────────────────────────────────────────

    @Test
    void updateLatestSymlink_issues_expected_cd_ln_command() throws Exception {
        // Capture the SSH command by routing through `sh -c "echo $@ > /tmp/file"`.
        File captureFile = tempDir.resolve("captured-cmd.txt").toFile();
        // Simulate ssh by invoking `sh -c 'printf "%s\n" "$@" > <file>' --`
        // and pass the SSH command as the only positional arg.
        ReleaseSupport.updateLatestSymlink(
                tempDir.toFile(), log,
                "/srv/ike-site/ike-platform/17",
                "sh", "-c",
                "printf '%s\\n' \"$@\" > " + captureFile.getAbsolutePath() + " ; exit 0",
                "-");

        String captured = java.nio.file.Files.readString(captureFile.toPath());
        // The captured output should contain the cd + ln invocation.
        assertThat(captured)
                .contains("cd /srv/ike-site/ike-platform")
                .contains("ln -snf 17 latest");
    }

    @Test
    void updateLatestSymlink_rejects_paths_outside_site_base() {
        assertThatThrownBy(() ->
                ReleaseSupport.updateLatestSymlink(
                        tempDir.toFile(), log,
                        "/etc/passwd"))
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("/srv/ike-site");
    }

    /** Minimal Log for tests — drops every call. */
    private static final class TestLog implements Log {
        @Override public boolean isDebugEnabled() { return false; }
        @Override public void debug(CharSequence c) {}
        @Override public void debug(CharSequence c, Throwable e) {}
        @Override public void debug(Throwable e) {}
        @Override public void debug(java.util.function.Supplier<String> c) {}
        @Override public void debug(java.util.function.Supplier<String> c, Throwable e) {}
        @Override public boolean isInfoEnabled() { return false; }
        @Override public void info(CharSequence c) {}
        @Override public void info(CharSequence c, Throwable e) {}
        @Override public void info(Throwable e) {}
        @Override public void info(java.util.function.Supplier<String> c) {}
        @Override public void info(java.util.function.Supplier<String> c, Throwable e) {}
        @Override public boolean isWarnEnabled() { return false; }
        @Override public void warn(CharSequence c) {}
        @Override public void warn(CharSequence c, Throwable e) {}
        @Override public void warn(Throwable e) {}
        @Override public void warn(java.util.function.Supplier<String> c) {}
        @Override public void warn(java.util.function.Supplier<String> c, Throwable e) {}
        @Override public boolean isErrorEnabled() { return false; }
        @Override public void error(CharSequence c) {}
        @Override public void error(CharSequence c, Throwable e) {}
        @Override public void error(Throwable e) {}
        @Override public void error(java.util.function.Supplier<String> c) {}
        @Override public void error(java.util.function.Supplier<String> c, Throwable e) {}
    }
}
