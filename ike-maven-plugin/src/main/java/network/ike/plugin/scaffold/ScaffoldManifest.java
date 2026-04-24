package network.ike.plugin.scaffold;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * In-memory representation of a scaffold manifest.
 *
 * <p>Shipped as {@code scaffold-manifest.yaml} inside the
 * {@code ike-build-standards} scaffold zip (see #222). Consumed by the
 * {@code ike:scaffold-draft|publish|revert} mojo family to decide
 * what templates to install, where, under which policy.
 *
 * @param schema           manifest schema version (currently
 *                         {@link #CURRENT_SCHEMA})
 * @param standardsVersion {@code ike-build-standards} version that
 *                         produced this manifest (recorded into
 *                         lockfile entries on publish)
 * @param entries          ordered list of manifest entries; never
 *                         {@code null}, stored as an unmodifiable
 *                         copy
 */
public record ScaffoldManifest(
        int schema,
        String standardsVersion,
        List<ManifestEntry> entries) {

    /**
     * Current on-disk schema version. Bumps here must be paired with
     * a migration in {@code ScaffoldManifestIo}.
     */
    public static final int CURRENT_SCHEMA = 1;

    /**
     * Canonical constructor with validation and defensive copying.
     */
    public ScaffoldManifest {
        if (schema <= 0) {
            throw new IllegalArgumentException(
                    "schema must be positive");
        }
        Objects.requireNonNull(standardsVersion, "standardsVersion");
        if (standardsVersion.isBlank()) {
            throw new IllegalArgumentException(
                    "standardsVersion cannot be blank");
        }
        entries = entries == null
                ? Collections.emptyList()
                : List.copyOf(entries);
    }

    /**
     * Filter entries by scope.
     *
     * @param scope the scope to keep
     * @return manifest entries matching {@code scope}, in original
     *         order
     */
    public List<ManifestEntry> entriesInScope(ScaffoldScope scope) {
        Objects.requireNonNull(scope, "scope");
        return entries.stream()
                .filter(e -> e.scope() == scope)
                .toList();
    }
}
