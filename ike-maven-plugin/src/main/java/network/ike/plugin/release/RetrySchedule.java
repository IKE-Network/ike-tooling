package network.ike.plugin.release;

import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;

/**
 * Backoff-schedule parsing and pre-retry sleep helpers shared by the
 * Nexus and Central deploy retry loops.
 *
 * <p>Extracted from {@code ReleaseDraftMojo} during the Phase 4
 * Commit 2 (IKE-Network/ike-issues#489) so that {@code NexusPhase}
 * and {@code CentralPhase} can share a single canonical
 * implementation instead of either duplicating the logic or depending
 * back on the mojo.
 *
 * <p>Both methods are pure utilities with no instance state.
 */
public final class RetrySchedule {

    private RetrySchedule() {
    }

    /**
     * Parses a comma-separated list of non-negative integer seconds
     * into a backoff schedule array.
     *
     * <p>Accepts whitespace between entries. Each entry must parse as
     * a non-negative integer; zero is permitted (no sleep). The
     * resulting array has one entry per configured cycle gap, so a
     * schedule of {@code "60,300,900"} produces backoff
     * {@code {60, 300, 900}} — applied as the wait before retry
     * cycles 2, 3, and 4 respectively.
     *
     * @param property property name surfaced in error messages (e.g. {@code "ike.deploy.nexus.backoffSeconds"})
     * @param csv      the raw comma-separated value
     * @return parsed seconds array (length &gt;= 1)
     * @throws MojoException if {@code csv} is null or blank, an entry is non-integer, or an entry is negative
     */
    public static int[] parseSeconds(String property, String csv) {
        if (csv == null || csv.isBlank()) {
            throw new MojoException(property
                    + " must be a non-empty comma-separated list "
                    + "of non-negative integer seconds");
        }
        String[] parts = csv.split(",");
        int[] arr = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String trimmed = parts[i].trim();
            int value;
            try {
                value = Integer.parseInt(trimmed);
            } catch (NumberFormatException e) {
                throw new MojoException("Invalid " + property
                        + " entry '" + trimmed + "' — expected "
                        + "comma-separated non-negative integers "
                        + "(seconds)", e);
            }
            if (value < 0) {
                throw new MojoException(property
                        + " entries must be >= 0: " + trimmed);
            }
            arr[i] = value;
        }
        return arr;
    }

    /**
     * Sleeps before the next retry cycle, with a clear log line so
     * the operator knows the build isn't hung.
     *
     * <p>A {@code seconds} value of zero or less is a no-op (no sleep,
     * no log). On thread interrupt, restores the interrupt flag and
     * throws a {@link MojoException}.
     *
     * @param log         logger for the wait-announcement line
     * @param seconds     seconds to wait (0 = no sleep)
     * @param label       phase label ({@code "Nexus deploy"} / {@code "Maven Central deploy"})
     * @param nextAttempt the upcoming cycle number
     * @param maxAttempts configured max cycles
     */
    public static void sleepBefore(Log log, int seconds, String label,
                                   int nextAttempt, int maxAttempts) {
        if (seconds <= 0) {
            return;
        }
        log.info("Waiting " + seconds + "s before " + label
                + " cycle " + nextAttempt + "/" + maxAttempts
                + "...");
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MojoException("Interrupted while waiting to "
                    + "retry " + label, e);
        }
    }
}
