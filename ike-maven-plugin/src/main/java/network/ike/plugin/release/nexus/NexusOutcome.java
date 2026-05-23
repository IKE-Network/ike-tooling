package network.ike.plugin.release.nexus;

/**
 * Outcome of the Nexus deploy phase in the release pipeline.
 *
 * <p>Tracks attempt count, success state, and an optional skip reason
 * (non-null when the phase was deliberately bypassed via the
 * {@code ike.skipNexusDeploy} parameter or because credentials were
 * unavailable). Read by the post-deploy log and the release-report
 * renderer; not consumed downstream by the cascade itself.
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
 * @param succeeded  whether the Nexus deploy completed successfully
 * @param attempts   number of attempts made (zero before the first try)
 * @param skipReason human-readable reason if the phase was skipped, or {@code null} when not skipped
 */
public record NexusOutcome(boolean succeeded, int attempts, String skipReason) {

    /**
     * Returns the initial outcome — not succeeded, zero attempts, no skip reason.
     *
     * @return the initial {@code NexusOutcome}
     */
    public static NexusOutcome initial() {
        return new NexusOutcome(false, 0, null);
    }

    /**
     * Returns a copy of this outcome with the attempt count replaced.
     *
     * @param attempts the new attempt count
     * @return a new {@code NexusOutcome} with the updated attempt count
     */
    public NexusOutcome withAttempts(int attempts) {
        return new NexusOutcome(succeeded, attempts, skipReason);
    }

    /**
     * Returns a copy of this outcome with the success flag replaced.
     *
     * @param succeeded the new success flag
     * @return a new {@code NexusOutcome} with the updated success flag
     */
    public NexusOutcome withSucceeded(boolean succeeded) {
        return new NexusOutcome(succeeded, attempts, skipReason);
    }

    /**
     * Returns a copy of this outcome with the skip reason replaced.
     *
     * @param skipReason the new skip reason, or {@code null} to clear
     * @return a new {@code NexusOutcome} with the updated skip reason
     */
    public NexusOutcome withSkipReason(String skipReason) {
        return new NexusOutcome(succeeded, attempts, skipReason);
    }
}
