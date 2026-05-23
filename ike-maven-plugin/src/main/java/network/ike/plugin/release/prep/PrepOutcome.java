package network.ike.plugin.release.prep;

/**
 * Outcome of the {@link ReleasePrep} phase — values computed during
 * B1–B12 that downstream phases consume.
 *
 * <p>{@code draftMode} drives the orchestrator dispatch: when
 * {@code true}, {@code runGoal()} short-circuits to the draft renderer
 * and returns instead of continuing into {@code LocalPhase}. The
 * other fields (projectId, hasOrigin, releaseTimestamp) feed
 * downstream phases that need them (LocalPhase reads
 * {@code releaseTimestamp}; FinalizePhase reads {@code projectId}
 * and {@code hasOrigin}; logAudit reads {@code projectId}).
 *
 * <p>Carved out of {@code ReleaseDraftMojo} during the Phase 4
 * Commit 5 (IKE-Network/ike-issues#489).
 *
 * @param projectId         the Maven artifact id read from {@code rootPom}
 * @param hasOrigin         whether {@code origin} is configured on the working tree
 * @param releaseTimestamp  the ISO-8601 UTC timestamp derived from the current HEAD commit;
 *                          stamped into {@code project.build.outputTimestamp} for reproducibility
 * @param draftMode         {@code true} when the request is a draft preview — {@code runGoal()}
 *                          short-circuits to the draft renderer instead of running the
 *                          local/nexus/central/finalize phases
 */
public record PrepOutcome(
        String projectId,
        boolean hasOrigin,
        String releaseTimestamp,
        boolean draftMode) {
}
