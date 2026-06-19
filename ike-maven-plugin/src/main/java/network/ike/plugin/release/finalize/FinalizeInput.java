package network.ike.plugin.release.finalize;

import network.ike.plugin.CascadeBump;

import java.util.List;

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
 * @param foundationUpgrades the upstream-version bumps the release applied, surfaced as a
 *                        "Foundation upgrades" section in the GitHub Release notes so a
 *                        cascade-only rebuild never announces "no changes" (#706)
 */
public record FinalizeInput(boolean hasOrigin, String projectId, String releaseVersion,
                            List<CascadeBump> foundationUpgrades) {
}
