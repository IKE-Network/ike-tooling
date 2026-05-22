package network.ike.plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
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
    void script_creates_isolated_git_worktree_at_release_tag() {
        // The main worktree gets restored to main + bumped to
        // next-SNAPSHOT immediately after the spawn. Without an
        // isolated worktree at v<version>, JReleaser reads the
        // post-bump pom.xml, sees a snapshot version, and silently
        // skips the Sonatype deployer. v197 hit this — async fix
        // requires the worktree.
        String script = render();
        assertThat(script).contains(
                "git -C \"$GIT_ROOT\" worktree add \"$WORKTREE\" \"v$VERSION\"");
        assertThat(script).contains("WORKTREE_PARENT=\"$(mktemp -d");
    }

    @Test
    void script_cleans_up_worktree_on_exit() {
        // trap EXIT removes the worktree and parent temp dir whether
        // the run succeeds, fails, or is killed. Without this an
        // interrupted subprocess leaves stale git worktree metadata
        // that breaks subsequent runs ("'<path>' already exists").
        String script = render();
        assertThat(script).contains("trap cleanup EXIT");
        assertThat(script).contains(
                "git -C \"$GIT_ROOT\" worktree remove --force \"$WORKTREE\"");
    }

    @Test
    void script_stages_signed_artifacts_inside_worktree() {
        // Staging runs inside the worktree so target/staging-deploy
        // is built from the v<version> pom.xml, not the post-bump
        // pom on the main worktree.
        String script = render();
        assertThat(script).contains("cd \"$WORKTREE\"")
                .contains("\"$MVNW\" clean deploy -B -T 1")
                .contains("-P release,signArtifacts")
                .contains("-DaltDeploymentRepository=local::file://\"$WORKTREE/target/staging-deploy\"");
    }

    @Test
    void script_validates_deployment_actually_happened() {
        // JReleaser silently skips the deployer when configuration
        // disables it (e.g. snapshot version), with mvn exit 0. The
        // first async impl trusted exit 0 and recorded SUCCESS for
        // a no-op deploy (v197 case). Defense-in-depth: assert log
        // contains an upload-confirmation line AND lacks the
        // skip-warning line. Either check failing = retry-eligible.
        String script = render();
        assertThat(script).contains(
                "grep -q \"is not enabled. Skipping\" \"$attempt_log\"");
        assertThat(script).contains(
                "grep -q \"uploaded as deployment\" \"$attempt_log\"");
    }

    @Test
    void script_passes_bash_syntax_check(@TempDir Path tempDir)
            throws Exception {
        // `bash -n` parses without executing — catches HEREDOC slip-ups,
        // unclosed braces, mis-escaped %% sequences from the Java
        // text-block formatter. Cheap insurance against template
        // regressions in renderRetryScript.
        Path scriptFile = tempDir.resolve("deploy.sh");
        Files.writeString(scriptFile, render());
        Process p = new ProcessBuilder("bash", "-n",
                scriptFile.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(
                p.getInputStream().readAllBytes());
        int exit = p.waitFor();
        assertThat(exit)
                .as("bash -n output: %s", output)
                .isZero();
    }

    @Test
    void script_prunes_build_pom_artifacts_recursively() {
        // Maven 4 emits an extra -build.pom per module that must not
        // ship to Central (#445). Prune from staging dir before
        // JReleaser uploads.
        String script = render();
        assertThat(script).contains("find \"$WORKTREE/target/staging-deploy\"")
                .contains("-name '*-build.pom'")
                .contains("-delete");
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
    void script_refreshes_pending_per_cycle() {
        // Per-cycle PENDING refresh updates the attempts counter
        // so ike:central-status reflects retry progress in real time.
        String script = render();
        assertThat(script).contains("write_sentinel \"PENDING\" \"\"");
        assertThat(script).contains("Cycle $ATTEMPTS/$MAX_ATTEMPTS");
    }

    @Test
    void script_uses_cycle_not_attempt_in_log_output() {
        // The outer retry loop logs "cycle", never "attempt" — so
        // its log lines don't visually collide with JReleaser's own
        // "[mavenCentral] Attempt N of 101" status-poll lines.
        // IKE-Network/ike-issues#484 follow-up; jreleaser#2125.
        String script = render();
        assertThat(script).contains("Cycle $ATTEMPTS/$MAX_ATTEMPTS");
        assertThat(script).contains("SUCCESS on cycle $ATTEMPTS");
        assertThat(script).contains("FAILURE after $ATTEMPTS cycles");
        assertThat(script).contains("before next cycle");
    }

    @Test
    void script_writes_finished_only_on_terminal_state() {
        // A PENDING refresh must not write `finished` — that would
        // read as a completed deploy in ike:central-status. The
        // finish timestamp is gated behind a non-PENDING check.
        String script = render();
        assertThat(script).contains("if [ \"$state\" != \"PENDING\" ]; then");
        assertThat(script).contains("echo \"finished=$(date");
    }

    @Test
    void script_records_note_on_jreleaser_poll_timeout() {
        // A JReleaser "Deployment timeout exceeded" is not a deploy
        // failure (the upload succeeded). The script detects it,
        // keeps the cycle a success, and records a note on the
        // sentinel so ike:central-status flags publication as
        // unconfirmed. IKE-Network/ike-issues#484.
        String script = render();
        assertThat(script).contains(
                "grep -q \"Deployment timeout exceeded\" \"$attempt_log\"");
        assertThat(script).contains("DEPLOY_NOTE=\"note=");
        assertThat(script).contains("write_sentinel \"SUCCESS\" \"$DEPLOY_NOTE\"");
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
