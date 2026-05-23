package network.ike.plugin.release;

import org.apache.maven.api.plugin.MojoException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link RetrySchedule#parseSeconds(String, String)} — the
 * parser for the {@code ike.deploy.{nexus,central}.backoffSeconds}
 * properties added in IKE-Network/ike-issues#482 for the two-phase
 * Nexus-first deploy with retries.
 *
 * <p>Carved out of {@code ReleaseDraftMojoBackoffTest} during the
 * Phase 4 Commit 2 (IKE-Network/ike-issues#489) when the parser
 * moved from the mojo into a shared utility.
 *
 * <p>Covers the happy path, whitespace tolerance, the default values
 * baked into the {@code @Parameter} annotations, and the three error
 * modes (null/blank input, non-integer entry, negative value).
 */
class RetryScheduleTest {

    @Test
    void parses_comma_separated_seconds() {
        assertThat(RetrySchedule.parseSeconds(
                "ike.deploy.nexus.backoffSeconds", "30,120,300"))
                .containsExactly(30, 120, 300);
    }

    @Test
    void tolerates_whitespace_between_entries() {
        assertThat(RetrySchedule.parseSeconds(
                "ike.deploy.central.backoffSeconds",
                " 60, 300 , 900 ,1800,3600 "))
                .containsExactly(60, 300, 900, 1800, 3600);
    }

    @Test
    void parses_single_entry() {
        assertThat(RetrySchedule.parseSeconds(
                "ike.deploy.nexus.backoffSeconds", "45"))
                .containsExactly(45);
    }

    @Test
    void accepts_zero() {
        // Zero means no sleep before retry — useful for tests
        // that exercise the retry path without real waiting.
        assertThat(RetrySchedule.parseSeconds(
                "ike.deploy.nexus.backoffSeconds", "0,0"))
                .containsExactly(0, 0);
    }

    @Test
    void parses_default_nexus_value() {
        // Matches the @Parameter default for nexusDeployBackoffSeconds.
        assertThat(RetrySchedule.parseSeconds(
                "ike.deploy.nexus.backoffSeconds", "30,120,300"))
                .containsExactly(30, 120, 300);
    }

    @Test
    void parses_default_central_value() {
        // Matches the @Parameter default for centralDeployBackoffSeconds.
        // 1 m / 5 m / 15 m / 30 m / 60 m — sized to ride through
        // Sonatype validation-queue throttling.
        assertThat(RetrySchedule.parseSeconds(
                "ike.deploy.central.backoffSeconds",
                "60,300,900,1800,3600"))
                .containsExactly(60, 300, 900, 1800, 3600);
    }

    @Test
    void rejects_null() {
        assertThatThrownBy(() -> RetrySchedule.parseSeconds(
                "ike.deploy.nexus.backoffSeconds", null))
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("ike.deploy.nexus.backoffSeconds")
                .hasMessageContaining("non-empty");
    }

    @Test
    void rejects_blank() {
        assertThatThrownBy(() -> RetrySchedule.parseSeconds(
                "ike.deploy.central.backoffSeconds", "   "))
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("ike.deploy.central.backoffSeconds");
    }

    @Test
    void rejects_non_integer_entry() {
        assertThatThrownBy(() -> RetrySchedule.parseSeconds(
                "ike.deploy.nexus.backoffSeconds", "30,fast,300"))
                .isInstanceOf(MojoException.class)
                .hasMessageContaining("fast");
    }

    @Test
    void rejects_negative_entry() {
        assertThatThrownBy(() -> RetrySchedule.parseSeconds(
                "ike.deploy.nexus.backoffSeconds", "30,-1,300"))
                .isInstanceOf(MojoException.class)
                .hasMessageContaining(">= 0");
    }
}
