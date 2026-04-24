package network.ike.workspace;

/**
 * A proposed update to a POM's {@code <parent>} declaration.
 *
 * <p>Emitted for modules whose parent is an IKE-managed POM
 * (e.g., {@code network.ike.platform:ike-parent}). Applied before
 * property and literal upgrades in
 * {@code versions-upgrade-publish} so that inherited
 * {@code pluginManagement} is current before the rest of the POM
 * is evaluated.
 *
 * @param groupId     the parent POM's group id
 * @param artifactId  the parent POM's artifact id
 * @param fromVersion the current parent version
 * @param toVersion   the proposed new version; for
 *                    {@link VersionUpgradeStatus#PENDING_UPSTREAM} this is
 *                    a placeholder like
 *                    {@code "[pending <node> release]"}
 * @param status      upgrade state; see {@link VersionUpgradeStatus}
 * @param reason      human-readable explanation for
 *                    {@link VersionUpgradeStatus#BLOCKED} or
 *                    {@link VersionUpgradeStatus#PENDING_UPSTREAM};
 *                    null when status is {@link VersionUpgradeStatus#READY}
 */
public record ParentVersionUpgrade(
        String groupId,
        String artifactId,
        String fromVersion,
        String toVersion,
        VersionUpgradeStatus status,
        String reason
) {}
