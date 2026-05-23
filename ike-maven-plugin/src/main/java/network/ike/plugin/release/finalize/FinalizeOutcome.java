package network.ike.plugin.release.finalize;

/**
 * Outcome of the {@link FinalizePhase} — the last release steps that
 * make the release externally visible beyond Nexus.
 *
 * <p>Tracks whether the tag and main pushes succeeded and whether
 * a GitHub Release creation was attempted (failure of the
 * {@code gh release create} subprocess is a logged warning, not an
 * exception — the release is already shipped to Nexus by the time
 * {@code FinalizePhase} runs).
 *
 * <p>Carved out of {@code ReleaseDraftMojo} during the Phase 4
 * Commit 1 (IKE-Network/ike-issues#489). The handoff §6.3 mentions
 * additional fields ({@code siteGenerated}, {@code ghPagesPublished},
 * {@code milestoneClosed}); those belong to phases or sub-blocks
 * that have not been extracted yet and will be added when those
 * commits land.
 *
 * @param tagPushed              {@code true} when {@code git push origin v<version>} succeeded
 * @param mainPushed             {@code true} when {@code git push origin main} succeeded
 * @param githubReleaseAttempted {@code true} when GitHub Release creation was attempted;
 *                               the subprocess itself may have logged a warning and continued
 */
public record FinalizeOutcome(boolean tagPushed, boolean mainPushed, boolean githubReleaseAttempted) {
}
