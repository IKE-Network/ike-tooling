package network.ike.plugin;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ReleaseDraftMojo#renderRetryScript} — the bash
 * retry-loop body that runs as a detached subprocess for async
 * Maven Central deploys (IKE-Network/ike-issues#484).
 *
 * <p>The script must:
 * <ul>
 *   <li>Carry the configured values verbatim into the running
 *       script (max attempts, backoff list, paths).</li>
 *   <li>Use absolute paths everywhere — relative paths would
 *       break once the script's cwd changes during the run.</li>
 *   <li>Write the sentinel via an atomic rename pattern so a
 *       concurrent {@code ike:central-status} reader never
 *       observes a partially-written file.</li>
 *   <li>Use {@code mvnw jreleaser:deploy -N -B} — the {@code -N}
 *       (non-recursive) is critical: without it the goal runs
 *       per module and the second invocation fails with
 *       "artifacts already deployed".</li>
 * </ul>
 */
class ReleaseDraftMojoAsyncScriptTest {

    @Test
    void script_starts_with_shebang_and_set_safety() {
        String script = render();
        assertThat(script).startsWith("#!/bin/bash");
        assertThat(script).contains("set -uo pipefail");
    }

    @Test
    void script_embeds_max_attempts_and_backoff_list() {
        String script = render(5, new int[]{60, 300, 900, 1800, 3600});
        assertThat(script).contains("MAX_ATTEMPTS=5");
        assertThat(script).contains("BACKOFFS=(60 300 900 1800 3600)");
    }

    @Test
    void script_embeds_absolute_paths() {
        String script = render();
        // Each path placeholder appears in the format-string output
        // as an absolute path.
        assertThat(script).contains("SENTINEL=\"/tmp/cache/ike-tooling-197.properties\"");
        assertThat(script).contains("LOG=\"/tmp/cache/ike-tooling-197.log\"");
        assertThat(script).contains("GIT_ROOT=\"/repo/ike-tooling\"");
        assertThat(script).contains("MVNW=\"/repo/ike-tooling/mvnw\"");
    }

    @Test
    void script_invokes_jreleaser_deploy_non_recursive_batch() {
        // -N: critical for jreleaser, single bundle upload at the
        //     reactor root. Without it the second module's deploy
        //     fails "artifacts already deployed".
        // -B: batch mode, no interactive prompts.
        String script = render();
        assertThat(script).contains("\"$MVNW\" jreleaser:deploy -N -B");
    }

    @Test
    void script_uses_atomic_rename_for_sentinel_writes() {
        // The write_sentinel function must stage to a .tmp file
        // and `mv -f` so concurrent central-status readers never
        // see a half-written file.
        String script = render();
        assertThat(script).contains("local tmp=\"${SENTINEL}.tmp\"");
        assertThat(script).contains("mv -f \"$tmp\" \"$SENTINEL\"");
    }

    @Test
    void script_terminal_states_are_success_and_failure() {
        String script = render();
        assertThat(script).contains("write_sentinel \"SUCCESS\"");
        assertThat(script).contains("write_sentinel \"FAILURE\"");
    }

    @Test
    void script_refreshes_pending_per_attempt() {
        // Per-attempt PENDING refresh updates the attempts counter
        // so ike:central-status reflects retry progress in real time.
        String script = render();
        assertThat(script).contains("write_sentinel \"PENDING\" \"\"");
        assertThat(script).contains("Attempt $ATTEMPTS/$MAX_ATTEMPTS");
    }

    @Test
    void script_includes_artifact_coordinates() {
        String script = render();
        assertThat(script).contains("ARTIFACT_ID=\"ike-tooling\"");
        assertThat(script).contains("VERSION=\"197\"");
    }

    // ── Render helpers ──────────────────────────────────────────

    private static String render() {
        return render(5, new int[]{60, 300, 900, 1800, 3600});
    }

    private static String render(int maxAttempts, int[] backoff) {
        return ReleaseDraftMojo.renderRetryScript(
                Paths.get("/repo/ike-tooling"),
                Paths.get("/repo/ike-tooling/mvnw"),
                Paths.get("/tmp/cache/ike-tooling-197.properties"),
                Paths.get("/tmp/cache/ike-tooling-197.log"),
                "ike-tooling", "197",
                maxAttempts, backoff,
                Instant.parse("2026-05-21T19:00:00Z"));
    }
}
