package network.ike.plugin.scaffold;

import java.util.Collections;
import java.util.List;
import java.util.Map;
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
 * @param foundation       IKE-foundation version pins captured at
 *                         scaffold-build time — the
 *                         tested-together compatibility snapshot of
 *                         ike-parent + standard properties. Null
 *                         when the manifest doesn't declare a
 *                         {@code foundation:} section (#345)
 */
public record ScaffoldManifest(
        int schema,
        String standardsVersion,
        List<ManifestEntry> entries,
        Foundation foundation) {

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
     * Backward-compatible constructor (#345 added the {@code foundation}
     * field; pre-#345 callers passed three args and got a manifest
     * with no foundation pinning).
     *
     * @param schema           manifest schema version
     * @param standardsVersion ike-build-standards version that
     *                         produced this manifest
     * @param entries          ordered manifest entries
     */
    public ScaffoldManifest(int schema,
                             String standardsVersion,
                             List<ManifestEntry> entries) {
        this(schema, standardsVersion, entries, null);
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

    /**
     * IKE-foundation version pins baked into the scaffold zip at
     * release time (#345). Picking up scaffold version N means picking
     * up the foundation versions that were the latest-released at
     * the moment {@code ike-tooling N} cut its release — the
     * compatibility snapshot operators want for cross-cascade
     * consistency.
     *
     * <p>The {@code parent} field carries the workspace-level
     * {@code <parent>} target (typically
     * {@code network.ike.platform:ike-parent} at version N).
     * The {@code properties} map carries values for the
     * standard IKE foundation property names
     * ({@code ike-tooling.version}, {@code ike-docs.version},
     * {@code ike-platform.version}).
     *
     * @param parent     parent declaration coordinates + version
     * @param properties map of property names to expected values
     */
    public record Foundation(ParentRef parent,
                              Map<String, String> properties) {

        /** Canonical constructor with defensive copying. */
        public Foundation {
            properties = properties == null
                    ? Collections.emptyMap()
                    : Map.copyOf(properties);
        }
    }

    /**
     * Coordinates of an inheriting parent POM, used by
     * {@link Foundation}.
     *
     * @param groupId    parent groupId
     * @param artifactId parent artifactId
     * @param version    parent version
     */
    public record ParentRef(String groupId,
                             String artifactId,
                             String version) {

        /** Canonical constructor with null guards. */
        public ParentRef {
            Objects.requireNonNull(groupId, "groupId");
            Objects.requireNonNull(artifactId, "artifactId");
            Objects.requireNonNull(version, "version");
        }
    }
}
