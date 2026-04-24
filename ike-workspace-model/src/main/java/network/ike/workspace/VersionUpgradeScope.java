package network.ike.workspace;

/**
 * Scope of a {@link VersionUpgradePlan} — whether it covers a single
 * module or a full workspace.
 *
 * <p>Encoded in the plan's YAML header so
 * {@code versions-upgrade-publish} can verify that a workspace-scoped
 * plan is applied via {@code ws:versions-upgrade-publish} and a
 * module-scoped plan via {@code ike:versions-upgrade-publish}.
 */
public enum VersionUpgradeScope {

    /**
     * Plan covers a single Maven module. Typically has exactly one
     * entry in {@link VersionUpgradePlan#nodes()}.
     */
    MODULE,

    /**
     * Plan covers all nodes in a workspace, in topological order.
     * Entries for multiple nodes may reference each other via
     * {@link VersionUpgradeStatus#PENDING_UPSTREAM} placeholders.
     */
    WORKSPACE
}
