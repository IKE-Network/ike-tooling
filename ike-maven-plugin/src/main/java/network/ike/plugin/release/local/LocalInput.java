package network.ike.plugin.release.local;

/**
 * Inputs to the {@link LocalPhase} — values the prep stage computes
 * before the local phase runs.
 *
 * <p>These will move to {@code PrepOutcome} when the release-prep phase
 * is extracted in Commit 5 (IKE-Network/ike-issues#489). Carrying them
 * as a small input record for now keeps the seam between the still-
 * inline prep block and the extracted local phase explicit.
 *
 * @param oldVersion        the current POM version (i.e., {@code X-SNAPSHOT}) read from
 *                          {@code pom.xml} before any version mutation
 * @param releaseTimestamp  the commit-derived reproducible-build timestamp
 *                          (ISO-8601 UTC) stamped into
 *                          {@code project.build.outputTimestamp}
 * @param resuming          {@code true} when {@code runGoal()} detected we are already
 *                          on the {@code release/<version>} branch from a failed
 *                          earlier attempt — branch creation and version setting
 *                          are skipped; {@code restoreBackups} handles restore later
 */
public record LocalInput(String oldVersion, String releaseTimestamp, boolean resuming) {
}
