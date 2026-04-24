package network.ike.plugin.scaffold;

import java.time.Instant;
import java.util.Objects;

/**
 * Provenance record for a single element installed by the
 * {@link ScaffoldTier#MODEL_MANAGED MODEL_MANAGED} tier.
 *
 * <p>Each managed element is identified by a canonical path expression
 * that the relevant adapter understands — an XPath for settings.xml,
 * an OpenRewrite cursor path for a POM, a git-config key for a git
 * config. The {@code standardsVersion} records which
 * {@code ike-build-standards} version caused the element to be
 * written, enabling targeted removal when a later standards version
 * retires the element.
 *
 * @param path             adapter-specific canonical path expression
 *                         locating this element (e.g.
 *                         {@code pluginGroups/pluginGroup[.="network.ike.tooling"]})
 * @param installedAt      UTC timestamp when this element was first
 *                         installed; never {@code null}
 * @param standardsVersion {@code ike-build-standards} version that
 *                         declared the ensuring rule when this element
 *                         was last touched; never {@code null}
 */
public record ManagedElement(
        String path,
        Instant installedAt,
        String standardsVersion) {

    /**
     * Canonical constructor with validation.
     */
    public ManagedElement {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(installedAt, "installedAt");
        Objects.requireNonNull(standardsVersion, "standardsVersion");
        if (path.isBlank()) {
            throw new IllegalArgumentException(
                    "ManagedElement.path cannot be blank");
        }
        if (standardsVersion.isBlank()) {
            throw new IllegalArgumentException(
                    "ManagedElement.standardsVersion cannot be blank");
        }
    }
}
