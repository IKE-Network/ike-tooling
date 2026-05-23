package network.ike.plugin.release.finalize;

/**
 * Inputs to the {@link FinalizePhase}.
 *
 * <p>Captures the local state {@code runGoal()} accumulated through
 * the prep, local, and deploy phases that {@link FinalizePhase}
 * needs to push the tag + main and create the GitHub Release.
 *
 * <p>A future commit will replace these scalar inputs with phase
 * outcomes ({@code NexusOutcome}, {@code PrepOutcome}, ...) as each
 * phase is extracted (IKE-Network/ike-issues#489 Commits 2–6).
 *
 * @param hasOrigin       whether the working tree's git repo has an {@code origin} remote
 * @param projectId       the project's Maven artifact id
 * @param releaseVersion  the released version (no {@code -SNAPSHOT})
 */
public record FinalizeInput(boolean hasOrigin, String projectId, String releaseVersion) {
}
