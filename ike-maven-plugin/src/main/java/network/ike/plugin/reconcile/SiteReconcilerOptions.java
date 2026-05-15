package network.ike.plugin.reconcile;

import java.util.Map;
import java.util.Optional;

/**
 * Flag bag passed through {@link SiteContext} to site reconcilers.
 * Backs the convergence-pattern flag scheme defined in
 * IKE-Network/ike-issues#398 (mirroring the #393 ws-side scheme):
 *
 * <ul>
 *   <li><b>Default</b> (no flag): deploy current version + register
 *       on the landing page.</li>
 *   <li><b>Opt out</b>: {@code -D<optOutFlag>=false} — skip a single
 *       reconciler entirely (e.g. {@code -DupdateSite=false},
 *       {@code -DupdateRegistration=false}).</li>
 *   <li><b>Uninstall</b>: {@code -Dsite=removed} — invert the apply
 *       pass and tear down the deployed site + registration
 *       (subsumes the retired {@code clean-site} + {@code deregister-site}
 *       workflows).</li>
 * </ul>
 *
 * @param rawFlags map of flag-name → string value (already extracted
 *                 from Maven system properties by the calling Mojo)
 */
public record SiteReconcilerOptions(Map<String, String> rawFlags) {

    /** Property name that triggers the uninstall pass. */
    public static final String SITE_FLAG = "site";

    /** Property value on {@link #SITE_FLAG} that triggers uninstall. */
    public static final String SITE_REMOVED = "removed";

    /**
     * @param flag flag name (without {@code -D} prefix)
     * @return true if the flag is present with value {@code "false"}
     */
    public boolean isOptedOut(String flag) {
        return "false".equals(rawFlags.get(flag));
    }

    /**
     * @param flag pin-flag name (without {@code -D} prefix)
     * @return the pinned value, or empty if no pin was provided
     */
    public Optional<String> pin(String flag) {
        return Optional.ofNullable(rawFlags.get(flag));
    }

    /**
     * @return true if {@code -Dsite=removed} was supplied — the
     *         publish pass should run as an uninstall instead of a
     *         forward deploy
     */
    public boolean isUninstall() {
        return SITE_REMOVED.equals(rawFlags.get(SITE_FLAG));
    }

    /**
     * @return an options bag with no flags set
     */
    public static SiteReconcilerOptions empty() {
        return new SiteReconcilerOptions(Map.of());
    }
}
