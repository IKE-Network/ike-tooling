package network.ike.plugin.reconcile;

/**
 * One dimension of site-state reconciliation under the convergence
 * pattern ({@code ike:site-{draft,publish}}, #398).
 *
 * <p>Each reconciler owns a single conceptual dimension of deployed
 * site state (the project's own site at its versioned URL, its
 * registration on the IKE Network landing page, leftover stale site
 * directories on the deploy server) and has two operations:
 * {@link #detect} (read-only, used by {@code site-draft}) and
 * {@link #apply} (mutating, used by {@code site-publish}).
 *
 * <p>The pattern intentionally subsumes what were previously
 * standalone Maven goals ({@code deploy-site-*}, {@code register-site-*},
 * {@code deregister-site-*}, {@code clean-site}): each retired goal's
 * logic becomes a single {@code SiteReconciler} implementation, and
 * {@code site-publish} iterates the {@link SiteReconcilerRegistry} to
 * apply them in order.
 *
 * <p>This interface parallels {@code network.ike.plugin.ws.reconcile.Reconciler}
 * in the workspace plugin. The two are kept separate because the per-repo
 * {@link SiteContext} carries a different shape than the workspace plugin's
 * {@code WorkspaceContext} — sharing the interface would force both
 * plugins to fit one context shape. See #398 for the architecture
 * rationale (decision: duplicate, not share).
 *
 * @see SiteDriftReport
 * @see SiteContext
 * @see SiteReconcilerRegistry
 */
public interface SiteReconciler {

    /**
     * Human-readable name of the dimension this reconciler owns.
     * Used as the heading in {@code site-draft} output.
     *
     * @return the dimension label, e.g. "Deployed site version"
     */
    String dimension();

    /**
     * The Maven property name (without the {@code -D} prefix) that
     * opts out of this reconciler's apply pass. Setting the property
     * to {@code "false"} skips this dimension on a given
     * {@code site-publish} invocation.
     *
     * @return the opt-out flag name, e.g. {@code "updateSite"}
     */
    String optOutFlag();

    /**
     * Inspect deployed site state and report any drift this reconciler
     * would correct. Read-only — must not mutate any remote state.
     *
     * @param ctx the per-repo site context
     * @return drift report; {@link SiteDriftReport#noDrift} if nothing to do
     */
    SiteDriftReport detect(SiteContext ctx);

    /**
     * Apply reconciliation. Caller is responsible for checking
     * {@link SiteReconcilerOptions#isOptedOut} before invoking;
     * implementations may also re-check defensively.
     *
     * @param ctx the per-repo site context
     */
    void apply(SiteContext ctx);

    /**
     * Apply the "uninstall" variant of this reconciler — used by
     * {@code site-publish -Dsite=removed} to tear down the deployed
     * site and its registration. Default implementation does nothing
     * (reconcilers that only forward-deploy can be inverted by the
     * stale-cleanup reconciler instead).
     *
     * @param ctx the per-repo site context
     */
    default void uninstall(SiteContext ctx) {
        // Default: no inverse operation. Cleanup-style reconcilers
        // override; forward-only ones leave it to the caller to skip.
    }
}
