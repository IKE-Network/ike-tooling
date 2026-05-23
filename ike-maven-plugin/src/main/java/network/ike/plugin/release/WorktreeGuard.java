package network.ike.plugin.release;

import network.ike.plugin.ReleaseSupport;

/**
 * AutoCloseable wrapper around the release pipeline's worktree
 * detach/restore boundary.
 *
 * <p>Acquired via {@link #detach(ReleaseContext, String, Runnable)} —
 * the factory runs {@code git checkout <releaseTag>}, leaving the
 * worktree pointed at the release tag. {@link #close()} stashes any
 * foreign worktree changes (delegated to the caller-supplied
 * {@code Runnable}) and then runs {@code git checkout main} to
 * restore the worktree.
 *
 * <p>Use as a try-with-resources around the externally-visible
 * deploy portion of the release pipeline:
 *
 * <pre>
 * try (WorktreeGuard guard = WorktreeGuard.detach(ctx, "v" + version,
 *         () -&gt; stashForeignWorktreeChanges(ctx, version))) {
 *     // site generation, gh-pages publish, Nexus/Central deploys
 * }
 * // worktree is restored to main, regardless of how the block exited
 * </pre>
 *
 * <p>If the detach itself fails, the factory throws and no guard
 * instance is returned — the caller's try-with-resources block
 * never enters and no cleanup runs, matching the prior behavior
 * where a failed detach left no work to undo.
 *
 * <p>Carved out of {@code ReleaseDraftMojo} during the Phase 4 P3
 * prep commit (IKE-Network/ike-issues#489). The stash callback
 * preserves the existing {@code stashForeignWorktreeChanges} as-is
 * on the mojo so other code paths that invoke it are unchanged;
 * a future commit can migrate the stash logic into a sibling
 * helper once {@code LocalPhase} is extracted.
 */
public final class WorktreeGuard implements AutoCloseable {

    private final ReleaseContext ctx;
    private final Runnable foreignStash;

    private WorktreeGuard(ReleaseContext ctx, Runnable foreignStash) {
        this.ctx = ctx;
        this.foreignStash = foreignStash;
    }

    /**
     * Detaches the worktree to the given release tag.
     *
     * <p>Runs {@code git checkout <releaseTag>} from
     * {@code ctx.gitRoot()}. If the checkout fails, this method
     * throws and no guard instance is returned.
     *
     * @param ctx          the release context carrying the git root and logger
     * @param releaseTag   the tag to detach to (typically {@code "v" + version})
     * @param foreignStash callback invoked from {@link #close()} before the
     *                     {@code git checkout main} step; intended to stash
     *                     foreign mid-flight worktree changes that would
     *                     otherwise block the checkout
     * @return a new {@code WorktreeGuard} ready to be closed
     */
    public static WorktreeGuard detach(ReleaseContext ctx, String releaseTag, Runnable foreignStash) {
        ReleaseSupport.exec(ctx.gitRoot(), ctx.log(), "git", "checkout", releaseTag);
        return new WorktreeGuard(ctx, foreignStash);
    }

    /**
     * Restores the worktree to {@code main}.
     *
     * <p>Runs the {@code foreignStash} callback to clear any
     * mid-flight worktree changes, then runs {@code git checkout main}.
     * Called automatically when the try-with-resources block exits,
     * regardless of whether the block exited normally or by exception.
     */
    @Override
    public void close() {
        foreignStash.run();
        ReleaseSupport.exec(ctx.gitRoot(), ctx.log(), "git", "checkout", "main");
    }
}
