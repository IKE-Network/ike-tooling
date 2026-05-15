package network.ike.plugin.reconcile;

import java.util.List;

/**
 * Compile-time registry of the site-level reconcilers iterated by
 * {@code ike:site-draft} (report) and {@code ike:site-publish}
 * (apply).
 *
 * <p>Order matters: reconcilers run in the order returned by
 * {@link #all()}. The forward-deploy pass runs deployed-site first
 * (so the site URL is reachable) and registration second (so the
 * landing page can link to a live target). The uninstall pass reverses
 * this order — landing-page deregistration first (so users don't see a
 * broken link), then stale site cleanup last.
 *
 * <p>Parallels {@code network.ike.plugin.ws.reconcile.ReconcilerRegistry}
 * but at the per-repo scope. See {@link SiteReconciler} for why the
 * two are intentionally not shared.
 *
 * @see SiteReconciler
 */
public final class SiteReconcilerRegistry {

    private SiteReconcilerRegistry() {}

    /**
     * Reconcilers in forward-deploy order. {@code site-publish}
     * (default) iterates this list and calls {@link SiteReconciler#apply}
     * on each.
     *
     * @return the ordered list of all registered reconcilers
     */
    public static List<SiteReconciler> all() {
        return List.of(
                new DeployedSiteReconciler(),
                new LandingPageRegistrationReconciler(),
                new StaleSiteCleanupReconciler()
        );
    }

    /**
     * Reconcilers in uninstall order — landing-page deregistration
     * first (so users don't see a link to a removed site), stale
     * cleanup last. {@code site-publish -Dsite=removed} iterates this
     * list and calls {@link SiteReconciler#uninstall} on each.
     *
     * @return the ordered list in uninstall order
     */
    public static List<SiteReconciler> uninstallOrder() {
        return List.of(
                new LandingPageRegistrationReconciler(),
                new StaleSiteCleanupReconciler(),
                new DeployedSiteReconciler()
        );
    }
}
