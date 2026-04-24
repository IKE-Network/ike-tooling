package network.ike.workspace;

/**
 * A proposed update to a literally-versioned coordinate in a POM
 * (one where {@code <version>} is a hard-coded string, not a
 * {@code ${property}} reference).
 *
 * <p>Literal versions should be rare in disciplined IKE POMs —
 * property-driven coordinates are preferred. This entry covers the
 * remaining cases: the {@code extensions=true} plugin version in
 * {@code ike-parent} (which Maven 4 requires to be literal because
 * it loads extensions before property interpolation), and any drift
 * the draft phase detects.
 *
 * @param groupId     the coordinate's group id
 * @param artifactId  the coordinate's artifact id
 * @param location    human-readable location within the POM
 *                    ({@code "dependency"}, {@code "plugin"},
 *                    {@code "pluginManagement"},
 *                    {@code "dependencyManagement"})
 * @param fromVersion the current literal version
 * @param toVersion   the proposed new literal version
 * @param status      upgrade state; see {@link VersionUpgradeStatus}
 * @param reason      human-readable explanation for
 *                    {@link VersionUpgradeStatus#BLOCKED} or
 *                    {@link VersionUpgradeStatus#PENDING_UPSTREAM};
 *                    null when status is {@link VersionUpgradeStatus#READY}
 */
public record LiteralVersionUpgrade(
        String groupId,
        String artifactId,
        String location,
        String fromVersion,
        String toVersion,
        VersionUpgradeStatus status,
        String reason
) {}
