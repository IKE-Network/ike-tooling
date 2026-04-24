package network.ike.workspace;

/**
 * State of a single proposed upgrade in a {@link VersionUpgradePlan}.
 *
 * <p>The status is set by {@code versions-upgrade-draft} based on
 * ruleset evaluation and workspace topology. {@code versions-upgrade-publish}
 * applies {@link #READY} entries and skips the others.
 */
public enum VersionUpgradeStatus {

    /**
     * Upgrade is applicable and will be applied by
     * {@code versions-upgrade-publish}.
     */
    READY,

    /**
     * Upgrade was considered but rejected by the active ruleset
     * (e.g., major-version jump when {@code allowMajorUpdates=false},
     * or matched a blocklist entry). Retained in the plan for
     * transparency; not applied.
     */
    BLOCKED,

    /**
     * Upgrade target depends on an earlier node's release in the
     * same cascade. The target version is not yet known; it will be
     * resolved during {@code ws:versions-upgrade-publish} once the
     * upstream node releases.
     */
    PENDING_UPSTREAM
}
