package network.ike.plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link CentralStatusMojo}'s pure formatting helpers
 * (IKE-Network/ike-issues#484). The execute() body is integration-
 * tested by the v197 release dogfooding itself; here we cover the
 * stable shape of the output rows that operators read and that a
 * potential CI parser could match against.
 */
class CentralStatusMojoTest {

    @TempDir
    Path tempDir;

    @Test
    void formatDuration_under_a_minute_renders_seconds() {
        assertThat(CentralStatusMojo.formatDuration(
                Duration.ofSeconds(42))).isEqualTo("42s");
    }

    @Test
    void formatDuration_minute_to_hour_renders_m_ss() {
        assertThat(CentralStatusMojo.formatDuration(
                Duration.ofSeconds(184))).isEqualTo("3m04s");
    }

    @Test
    void formatDuration_over_an_hour_renders_h_mm() {
        assertThat(CentralStatusMojo.formatDuration(
                Duration.ofSeconds(4360))).isEqualTo("1h12m");
    }

    @Test
    void formatRow_pending_shows_clock_and_running_for() {
        Instant started = Instant.parse("2026-05-21T19:00:00Z");
        Instant now = Instant.parse("2026-05-21T19:03:42Z");
        CentralDeploySentinel pending = CentralDeploySentinel.builder()
                .state(CentralDeploySentinel.State.PENDING)
                .artifactId("ike-tooling")
                .version("197")
                .started(started)
                .attempts(2)
                .maxAttempts(5)
                .path(tempDir.resolve("ike-tooling-197.properties"))
                .build();

        String row = CentralStatusMojo.formatRow(pending, now);

        assertThat(row).contains("⏳")
                .contains("ike-tooling-197")
                .contains("PENDING")
                .contains("cycle 2/5")
                .contains("running for 3m42s");
    }

    @Test
    void formatRow_success_shows_check_and_took() {
        Instant started = Instant.parse("2026-05-21T19:00:00Z");
        Instant finished = Instant.parse("2026-05-21T19:08:15Z");
        CentralDeploySentinel succeeded = CentralDeploySentinel.builder()
                .state(CentralDeploySentinel.State.SUCCESS)
                .artifactId("ike-docs")
                .version("42")
                .started(started)
                .finished(finished)
                .attempts(1)
                .maxAttempts(5)
                .path(tempDir.resolve("ike-docs-42.properties"))
                .build();

        String row = CentralStatusMojo.formatRow(succeeded,
                Instant.parse("2026-05-21T20:00:00Z"));

        assertThat(row).contains("✅")
                .contains("ike-docs-42")
                .contains("SUCCESS")
                .contains("cycle 1/5")
                .contains("took 8m15s");
    }

    @Test
    void formatRow_failure_shows_cross_and_elapsed() {
        Instant started = Instant.parse("2026-05-21T19:00:00Z");
        Instant finished = Instant.parse("2026-05-21T20:50:00Z");
        CentralDeploySentinel failed = CentralDeploySentinel.builder()
                .state(CentralDeploySentinel.State.FAILURE)
                .artifactId("ike-platform")
                .version("9")
                .started(started)
                .finished(finished)
                .attempts(5)
                .maxAttempts(5)
                .lastError("HTTP 503")
                .path(tempDir.resolve("ike-platform-9.properties"))
                .build();

        String row = CentralStatusMojo.formatRow(failed,
                Instant.parse("2026-05-21T21:00:00Z"));

        assertThat(row).contains("❌")
                .contains("ike-platform-9")
                .contains("FAILURE")
                .contains("took 1h50m");
    }
}
