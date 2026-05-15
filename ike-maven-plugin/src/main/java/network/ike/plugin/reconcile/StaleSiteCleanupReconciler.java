package network.ike.plugin.reconcile;

import network.ike.plugin.ReleaseSupport;
import org.apache.maven.api.plugin.MojoException;

import java.util.List;

/**
 * Reconciler that removes deployed-site directories from the legacy
 * scpexe deploy server ({@link ReleaseSupport#SITE_DISK_BASE} on
 * {@link ReleaseSupport#SITE_SSH_HOST}).
 *
 * <p>Subsumes the retired {@code ike:clean-site} goal
 * (IKE-Network/ike-issues#398). Most projects no longer use the scpexe
 * mirror (#304 retired it as the canonical distribution channel in
 * favor of GitHub Pages), but the directory may still exist from
 * before that change and shows up in {@code ike:site-publish -Dsite=removed}
 * runs as cleanup work.
 *
 * <p><b>Detect</b>: read-only — does not SSH out to enumerate stale
 * dirs (that would slow {@code site-draft} for every project even when
 * nothing is wrong). Always reports {@link SiteDriftReport#noDrift}
 * during a forward-deploy draft; the uninstall path (triggered by
 * {@code -Dsite=removed}) is the only time stale cleanup is in scope.
 *
 * <p><b>Apply</b>: forward-deploy direction does nothing (this
 * reconciler is uninstall-only). The forward {@link DeployedSiteReconciler}
 * already publishes a fresh site.
 *
 * <p><b>Uninstall</b>: SSH out and remove
 * {@code /srv/ike-site/<projectId>/} (the release subtree). The
 * {@code .staging} / {@code .old} suffixes are handled by the same
 * {@code ssh rm -rf} since they're inside the same project tree.
 */
public class StaleSiteCleanupReconciler implements SiteReconciler {

    @Override
    public String dimension() {
        return "Stale deployed-site directories";
    }

    @Override
    public String optOutFlag() {
        return "cleanSite";
    }

    @Override
    public SiteDriftReport detect(SiteContext ctx) {
        // No remote enumeration in detect — keep site-draft snappy.
        // The uninstall path (-Dsite=removed) is the only place this
        // reconciler runs work, and that path doesn't go through
        // detect() at all.
        return SiteDriftReport.noDrift(dimension());
    }

    @Override
    public void apply(SiteContext ctx) {
        // Forward deploy: nothing to do. DeployedSiteReconciler
        // publishes fresh content; the prior scpexe mirror was retired
        // in #304 and no longer needs per-deploy cleanup.
        if (ctx.options().isOptedOut(optOutFlag())) {
            return;
        }
    }

    @Override
    public void uninstall(SiteContext ctx) {
        if (ctx.options().isOptedOut(optOutFlag())) {
            ctx.log().info("  " + dimension() + ": skipped (opted out via -D"
                    + optOutFlag() + "=false)");
            return;
        }
        String projectId = ctx.projectId();
        if (projectId == null || projectId.isBlank()) {
            ctx.log().info("  " + dimension()
                    + ": skipped (no project id)");
            return;
        }
        String remotePath = ReleaseSupport.SITE_DISK_BASE + projectId;
        try {
            ReleaseSupport.cleanRemoteSiteDir(
                    ctx.gitRoot(), ctx.log(), remotePath);
            ctx.log().info("  " + dimension()
                    + ": removed " + remotePath);
        } catch (MojoException e) {
            // Likely "host not reachable" on machines without the
            // wireguard / proxy SSH config. Non-fatal — most projects
            // were never on the scpexe mirror.
            ctx.log().info("  " + dimension()
                    + ": scpexe mirror cleanup skipped ("
                    + e.getMessage() + ")");
        }
    }
}
