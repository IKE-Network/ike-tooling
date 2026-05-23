package network.ike.plugin.release.local;

/**
 * Outcome of the {@link LocalPhase} — the local-only release work
 * (POM rewrites, commit, tag, merge to main, post-release bump).
 *
 * <p>By the time this record is constructed, the irreversibility
 * boundary at B18 ({@code git tag v<version>}) has passed and the
 * release commit + tag + restore + merge + post-bump are all in
 * place locally. Externally-visible work (deploys, push,
 * GitHub Release) happens in later phases.
 *
 * <p>The handoff §6.3 anticipates additional fields
 * ({@code releaseCommit}, {@code mergeCommit}, {@code postBumpCommit}
 * SHAs, {@code stagingDir} for downstream site work). Those are
 * added when consumers need them; for Commit 4 the flags below
 * record which sub-steps ran.
 *
 * @param tag               the created git tag (e.g., {@code "v25"})
 * @param releaseCommitted  whether a release commit was created on the
 *                          {@code release/<version>} branch (false if resuming
 *                          and the commit was already present)
 * @param merged            whether {@code release/<version>} was merged back to {@code main}
 * @param postBumped        whether {@code main} received the post-release bump commit
 */
public record LocalOutcome(String tag, boolean releaseCommitted, boolean merged, boolean postBumped) {
}
