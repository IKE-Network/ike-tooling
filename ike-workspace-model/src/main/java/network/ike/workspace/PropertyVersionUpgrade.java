package network.ike.workspace;

/**
 * A proposed update to a POM property that drives a dependency or
 * plugin version (e.g., {@code ike-tooling.version}).
 *
 * <p>Property-driven upgrades are the primary mechanism for
 * version management in IKE POMs. Coordinates resolved via
 * {@code ${propertyName}} are updated atomically by changing
 * the property value.
 *
 * @param propertyName the property key as it appears in the POM
 *                     ({@code <properties>} section)
 * @param fromVersion  the current property value
 * @param toVersion    the proposed new value; for
 *                     {@link VersionUpgradeStatus#PENDING_UPSTREAM} entries
 *                     this is a placeholder like
 *                     {@code "[pending <node> release]"}
 * @param status       upgrade state; see {@link VersionUpgradeStatus}
 * @param reason       human-readable explanation for
 *                     {@link VersionUpgradeStatus#BLOCKED} or
 *                     {@link VersionUpgradeStatus#PENDING_UPSTREAM};
 *                     null when status is {@link VersionUpgradeStatus#READY}
 */
public record PropertyVersionUpgrade(
        String propertyName,
        String fromVersion,
        String toVersion,
        VersionUpgradeStatus status,
        String reason
) {}
