package network.ike.plugin;

import org.apache.maven.api.plugin.MojoException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link CentralDeploySentinel} — the per-release status
 * record persisted under {@code ~/.cache/ike-release/} so async
 * Maven Central deploys (IKE-Network/ike-issues#484) can be tracked
 * across Maven JVM invocations.
 *
 * <p>Covers: round-trip read/write, listAll ordering and robustness
 * to malformed entries, atomic rewrite (a partial write must not
 * corrupt the file an in-flight subprocess might be updating), and
 * the three error modes (missing state, missing required field,
 * invalid state name).
 */
class CentralDeploySentinelTest {

    @TempDir
    Path tempDir;

    @Test
    void path_resolves_to_artifactId_dash_version_dot_properties() {
        Path resolved = CentralDeploySentinel.resolvePath(
                tempDir, "ike-tooling", "196");
        assertThat(resolved).isEqualTo(
                tempDir.resolve("ike-tooling-196.properties"));
    }

    @Test
    void round_trips_pending_state() {
        Instant now = Instant.parse("2026-05-21T19:00:00Z");
        Path sentinelPath = CentralDeploySentinel.resolvePath(
                tempDir, "ike-tooling", "196");
        CentralDeploySentinel pending = CentralDeploySentinel.builder()
                .state(CentralDeploySentinel.State.PENDING)
                .artifactId("ike-tooling")
                .version("196")
                .started(now)
                .attempts(0)
                .maxAttempts(5)
                .logFile(tempDir.resolve("ike-tooling-196.log"))
                .pid(12345L)
                .path(sentinelPath)
                .build();
        pending.write();

        CentralDeploySentinel read = CentralDeploySentinel.read(
                sentinelPath);
        assertThat(read.state())
                .isEqualTo(CentralDeploySentinel.State.PENDING);
        assertThat(read.artifactId()).isEqualTo("ike-tooling");
        assertThat(read.version()).isEqualTo("196");
        assertThat(read.started()).isEqualTo(now);
        assertThat(read.finished()).isNull();
        assertThat(read.attempts()).isZero();
        assertThat(read.maxAttempts()).isEqualTo(5);
        assertThat(read.lastError()).isNull();
        assertThat(read.logFile()).isEqualTo(
                tempDir.resolve("ike-tooling-196.log"));
        assertThat(read.pid()).isEqualTo(12345L);
    }

    @Test
    void round_trips_success_state_with_finished_timestamp() {
        Instant started = Instant.parse("2026-05-21T19:00:00Z");
        Instant finished = Instant.parse("2026-05-21T19:08:30Z");
        Path sentinelPath = CentralDeploySentinel.resolvePath(
                tempDir, "ike-docs", "42");
        CentralDeploySentinel.builder()
                .state(CentralDeploySentinel.State.SUCCESS)
                .artifactId("ike-docs")
                .version("42")
                .started(started)
                .finished(finished)
                .attempts(2)
                .maxAttempts(5)
                .path(sentinelPath)
                .build()
                .write();

        CentralDeploySentinel read = CentralDeploySentinel.read(
                sentinelPath);
        assertThat(read.state())
                .isEqualTo(CentralDeploySentinel.State.SUCCESS);
        assertThat(read.finished()).isEqualTo(finished);
        assertThat(read.attempts()).isEqualTo(2);
    }

    @Test
    void round_trips_failure_state_with_lastError() {
        Path sentinelPath = CentralDeploySentinel.resolvePath(
                tempDir, "ike-platform", "9");
        CentralDeploySentinel.builder()
                .state(CentralDeploySentinel.State.FAILURE)
                .artifactId("ike-platform")
                .version("9")
                .started(Instant.parse("2026-05-21T19:00:00Z"))
                .finished(Instant.parse("2026-05-21T20:50:00Z"))
                .attempts(5)
                .maxAttempts(5)
                .lastError("exhausted 5 attempts; HTTP 503 on upload")
                .path(sentinelPath)
                .build()
                .write();

        CentralDeploySentinel read = CentralDeploySentinel.read(
                sentinelPath);
        assertThat(read.state())
                .isEqualTo(CentralDeploySentinel.State.FAILURE);
        assertThat(read.lastError())
                .contains("exhausted 5 attempts");
    }

    @Test
    void round_trips_note_on_success() {
        // The note carries a non-failure advisory — e.g. a SUCCESS
        // where JReleaser's publish poll timed out so the upload is
        // confirmed but PUBLISHED was never observed (#484).
        Path sentinelPath = CentralDeploySentinel.resolvePath(
                tempDir, "ike-tooling", "198");
        CentralDeploySentinel.builder()
                .state(CentralDeploySentinel.State.SUCCESS)
                .artifactId("ike-tooling")
                .version("198")
                .started(Instant.parse("2026-05-21T19:00:00Z"))
                .finished(Instant.parse("2026-05-21T19:35:00Z"))
                .attempts(1)
                .maxAttempts(5)
                .note("upload accepted by Sonatype; publication unconfirmed")
                .path(sentinelPath)
                .build()
                .write();

        CentralDeploySentinel read = CentralDeploySentinel.read(
                sentinelPath);
        assertThat(read.state())
                .isEqualTo(CentralDeploySentinel.State.SUCCESS);
        assertThat(read.note())
                .isEqualTo("upload accepted by Sonatype; "
                        + "publication unconfirmed");
    }

    @Test
    void note_is_null_when_not_set() {
        Path sentinelPath = CentralDeploySentinel.resolvePath(
                tempDir, "ike-docs", "52");
        CentralDeploySentinel.builder()
                .state(CentralDeploySentinel.State.SUCCESS)
                .artifactId("ike-docs")
                .version("52")
                .started(Instant.parse("2026-05-21T19:00:00Z"))
                .finished(Instant.parse("2026-05-21T19:05:00Z"))
                .maxAttempts(5)
                .path(sentinelPath)
                .build()
                .write();

        assertThat(CentralDeploySentinel.read(sentinelPath).note())
                .isNull();
    }

    @Test
    void rejects_missing_state() throws Exception {
        Path sentinelPath = tempDir.resolve("no-state.properties");
        Files.writeString(sentinelPath,
                "artifactId=foo\nversion=1\nstarted=2026-05-21T19:00:00Z\n");
        assertThatThrownBy(() ->
                CentralDeploySentinel.read(sentinelPath))
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("state");
    }

    @Test
    void rejects_invalid_state_value() throws Exception {
        Path sentinelPath = tempDir.resolve("bad-state.properties");
        Files.writeString(sentinelPath,
                "state=RUNNING\nartifactId=foo\nversion=1\n"
                        + "started=2026-05-21T19:00:00Z\n");
        assertThatThrownBy(() ->
                CentralDeploySentinel.read(sentinelPath))
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("RUNNING")
                .hasMessageContaining("PENDING/SUCCESS/FAILURE");
    }

    @Test
    void rejects_missing_required_field() throws Exception {
        Path sentinelPath = tempDir.resolve("no-version.properties");
        Files.writeString(sentinelPath,
                "state=PENDING\nartifactId=foo\n"
                        + "started=2026-05-21T19:00:00Z\n");
        assertThatThrownBy(() ->
                CentralDeploySentinel.read(sentinelPath))
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("version");
    }

    @Test
    void listAll_returns_empty_when_dir_missing() {
        assertThat(CentralDeploySentinel.listAll(
                tempDir.resolve("does-not-exist")))
                .isEmpty();
    }

    @Test
    void listAll_returns_empty_for_empty_dir() {
        assertThat(CentralDeploySentinel.listAll(tempDir)).isEmpty();
    }

    @Test
    void listAll_orders_newest_first() {
        Instant t1 = Instant.parse("2026-05-21T19:00:00Z");
        Instant t2 = Instant.parse("2026-05-21T19:30:00Z");
        Instant t3 = Instant.parse("2026-05-21T20:00:00Z");
        writeSentinel("ike-tooling", "196", t1,
                CentralDeploySentinel.State.SUCCESS);
        writeSentinel("ike-docs", "42", t3,
                CentralDeploySentinel.State.PENDING);
        writeSentinel("ike-platform", "9", t2,
                CentralDeploySentinel.State.SUCCESS);

        List<CentralDeploySentinel> all = CentralDeploySentinel
                .listAll(tempDir);

        assertThat(all).hasSize(3);
        assertThat(all.get(0).artifactId()).isEqualTo("ike-docs");
        assertThat(all.get(1).artifactId()).isEqualTo("ike-platform");
        assertThat(all.get(2).artifactId()).isEqualTo("ike-tooling");
    }

    @Test
    void listAll_skips_malformed_files() throws Exception {
        // A real in-flight subprocess shouldn't leave files like this,
        // but the atomic-rename pattern + a kill -9 between unlink and
        // rename could in principle. listAll must not throw — central-
        // status would be unusable on a workspace with one bad sentinel.
        writeSentinel("good", "1",
                Instant.parse("2026-05-21T19:00:00Z"),
                CentralDeploySentinel.State.SUCCESS);
        Files.writeString(tempDir.resolve("bad-sentinel.properties"),
                "not=a=valid sentinel\n");
        Files.writeString(tempDir.resolve("not-a-sentinel.txt"),
                "ignored by extension filter");

        List<CentralDeploySentinel> all = CentralDeploySentinel
                .listAll(tempDir);

        assertThat(all).hasSize(1);
        assertThat(all.get(0).artifactId()).isEqualTo("good");
    }

    @Test
    void write_is_atomic_replace() throws Exception {
        // After write, no .tmp file remains. (The atomic-rename
        // pattern matters because the subprocess updates the same
        // sentinel on every attempt; concurrent readers from
        // central-status must never see a half-written file.)
        Path sentinelPath = CentralDeploySentinel.resolvePath(
                tempDir, "ike-tooling", "196");
        CentralDeploySentinel.builder()
                .state(CentralDeploySentinel.State.PENDING)
                .artifactId("ike-tooling")
                .version("196")
                .started(Instant.parse("2026-05-21T19:00:00Z"))
                .maxAttempts(5)
                .path(sentinelPath)
                .build()
                .write();

        try (Stream<Path> stream = Files.list(tempDir)) {
            assertThat(stream.toList())
                    .extracting(p -> p.getFileName().toString())
                    .containsExactly("ike-tooling-196.properties");
        }
    }

    @Test
    void toBuilder_preserves_all_fields() {
        Path sentinelPath = CentralDeploySentinel.resolvePath(
                tempDir, "ike-tooling", "196");
        CentralDeploySentinel original = CentralDeploySentinel.builder()
                .state(CentralDeploySentinel.State.PENDING)
                .artifactId("ike-tooling")
                .version("196")
                .started(Instant.parse("2026-05-21T19:00:00Z"))
                .attempts(2)
                .maxAttempts(5)
                .logFile(tempDir.resolve("log"))
                .pid(99L)
                .path(sentinelPath)
                .build();

        CentralDeploySentinel updated = original.toBuilder()
                .state(CentralDeploySentinel.State.SUCCESS)
                .finished(Instant.parse("2026-05-21T19:10:00Z"))
                .build();

        assertThat(updated.state())
                .isEqualTo(CentralDeploySentinel.State.SUCCESS);
        assertThat(updated.artifactId()).isEqualTo("ike-tooling");
        assertThat(updated.attempts()).isEqualTo(2);
        assertThat(updated.maxAttempts()).isEqualTo(5);
        assertThat(updated.pid()).isEqualTo(99L);
    }

    // ── Helpers ──────────────────────────────────────────────

    private void writeSentinel(String artifactId, String version,
                                Instant started,
                                CentralDeploySentinel.State state) {
        Path path = CentralDeploySentinel.resolvePath(
                tempDir, artifactId, version);
        CentralDeploySentinel.builder()
                .state(state)
                .artifactId(artifactId)
                .version(version)
                .started(started)
                .maxAttempts(5)
                .path(path)
                .build()
                .write();
    }
}
