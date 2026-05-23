package network.ike.plugin.release.central;

import java.nio.file.Path;

/**
 * Outcome of the Maven Central deploy phase in the release pipeline.
 *
 * <p>Central deploys have two paths: a synchronous retry loop and a
 * detached async-bash spawn (IKE-Network/ike-issues#484). Both share
 * this outcome record. When the async path is taken,
 * {@code asyncSpawned} is {@code true} and {@code sentinelPath} +
 * {@code logPath} point to the IPC files that {@code ike:central-status}
 * reads to discover post-process completion.
 *
 * <p>Instances are immutable. Callers update state by constructing a
 * new instance via the {@code with*} helpers. {@link #initial()}
 * returns the "did not run" state — appropriate for a draft preview
 * or a release aborted before the deploy phase.
 *
 * <p>Carved out of {@code ReleaseDraftMojo} during the Phase 4
 * decomposition (IKE-Network/ike-issues#489 P1) so that downstream
 * commits can replace mojo fields with phase-returned outcomes
 * without further reshaping.
 *
 * @param succeeded      whether the Central deploy completed successfully (sync path) or was successfully spawned (async path)
 * @param attempts       number of attempts made (zero before the first try)
 * @param asyncSpawned   whether the deploy was spawned as a detached subprocess (#484)
 * @param sentinelPath   IPC sentinel file path when {@code asyncSpawned} is true, otherwise {@code null}
 * @param logPath        log file the async subprocess streams to when {@code asyncSpawned} is true, otherwise {@code null}
 * @param skipReason     human-readable reason if the phase was skipped, or {@code null} when not skipped
 * @param failureSummary human-readable summary when the phase was attempted and exhausted retries, or {@code null} when not failed
 */
public record CentralOutcome(
        boolean succeeded,
        int attempts,
        boolean asyncSpawned,
        Path sentinelPath,
        Path logPath,
        String skipReason,
        String failureSummary) {

    /**
     * Returns the initial outcome — not succeeded, zero attempts, no async spawn, no skip, no failure.
     *
     * @return the initial {@code CentralOutcome}
     */
    public static CentralOutcome initial() {
        return new CentralOutcome(false, 0, false, null, null, null, null);
    }

    /**
     * Returns a copy of this outcome with the attempt count replaced.
     *
     * @param attempts the new attempt count
     * @return a new {@code CentralOutcome} with the updated attempt count
     */
    public CentralOutcome withAttempts(int attempts) {
        return new CentralOutcome(succeeded, attempts, asyncSpawned, sentinelPath, logPath, skipReason, failureSummary);
    }

    /**
     * Returns a copy of this outcome with the success flag replaced.
     *
     * @param succeeded the new success flag
     * @return a new {@code CentralOutcome} with the updated success flag
     */
    public CentralOutcome withSucceeded(boolean succeeded) {
        return new CentralOutcome(succeeded, attempts, asyncSpawned, sentinelPath, logPath, skipReason, failureSummary);
    }

    /**
     * Returns a copy of this outcome with the {@code asyncSpawned} flag replaced.
     *
     * @param asyncSpawned the new async-spawn flag
     * @return a new {@code CentralOutcome} with the updated async-spawn flag
     */
    public CentralOutcome withAsyncSpawned(boolean asyncSpawned) {
        return new CentralOutcome(succeeded, attempts, asyncSpawned, sentinelPath, logPath, skipReason, failureSummary);
    }

    /**
     * Returns a copy of this outcome with the sentinel-file path replaced.
     *
     * @param sentinelPath the new sentinel path, or {@code null} to clear
     * @return a new {@code CentralOutcome} with the updated sentinel path
     */
    public CentralOutcome withSentinelPath(Path sentinelPath) {
        return new CentralOutcome(succeeded, attempts, asyncSpawned, sentinelPath, logPath, skipReason, failureSummary);
    }

    /**
     * Returns a copy of this outcome with the log-file path replaced.
     *
     * @param logPath the new log path, or {@code null} to clear
     * @return a new {@code CentralOutcome} with the updated log path
     */
    public CentralOutcome withLogPath(Path logPath) {
        return new CentralOutcome(succeeded, attempts, asyncSpawned, sentinelPath, logPath, skipReason, failureSummary);
    }

    /**
     * Returns a copy of this outcome with the skip reason replaced.
     *
     * @param skipReason the new skip reason, or {@code null} to clear
     * @return a new {@code CentralOutcome} with the updated skip reason
     */
    public CentralOutcome withSkipReason(String skipReason) {
        return new CentralOutcome(succeeded, attempts, asyncSpawned, sentinelPath, logPath, skipReason, failureSummary);
    }

    /**
     * Returns a copy of this outcome with the failure summary replaced.
     *
     * @param failureSummary the new failure summary, or {@code null} to clear
     * @return a new {@code CentralOutcome} with the updated failure summary
     */
    public CentralOutcome withFailureSummary(String failureSummary) {
        return new CentralOutcome(succeeded, attempts, asyncSpawned, sentinelPath, logPath, skipReason, failureSummary);
    }
}
