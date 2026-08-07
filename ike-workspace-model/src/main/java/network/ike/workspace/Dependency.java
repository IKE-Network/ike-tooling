package network.ike.workspace;

import java.util.Set;

/**
 * An inter-repository dependency declared in a subproject's
 * {@code depends-on} list.
 *
 * <p>Relationship semantics (see {@code IKE-WORKSPACE.md}):
 * <ul>
 *   <li>{@code build} — needs the upstream's compiled artifacts;
 *       orders the workspace build.</li>
 *   <li>{@code content} — references the upstream's architecture or
 *       concepts; a change may require only review.</li>
 *   <li>{@code tooling} — uses the upstream's CLI tools or Maven
 *       plugins.</li>
 *   <li>{@code bundle} — packages the upstream's artifact into an
 *       assembly at package time, resolved from the repository
 *       ({@code ~/.m2} or a snapshot repo) rather than built from the
 *       workspace. Excluded from build ordering and cycle detection,
 *       still traversed by cascade analysis
 *       (IKE-Network/ike-issues#963). Carries a resolution hazard: the
 *       workspace does not guarantee the artifact is built first.</li>
 * </ul>
 *
 * @param subproject      the name of the depended-on subproject
 * @param relationship    the nature of the dependency — one of
 *                        {@link #KNOWN_RELATIONSHIPS}
 * @param versionProperty optional POM property name that tracks the upstream
 *                        subproject's version (e.g., "ike-maven-plugin.version").
 *                        Used by {@code ike:ws-release} to update version
 *                        references after releasing an upstream subproject.
 *                        Null if no property tracking is needed.
 */
public record Dependency(
        String subproject,
        String relationship,
        String versionProperty
) {
    /**
     * The recognized {@code relationship:} values. {@code tool-time}
     * is the legacy spelling of {@code tooling}, kept for manifests
     * written before the standard settled on the shorter name.
     */
    public static final Set<String> KNOWN_RELATIONSHIPS =
            Set.of("build", "content", "tooling", "tool-time", "bundle");

    /**
     * Two-arg constructor for backwards compatibility (no version-property).
     *
     * @param subproject   the name of the depended-on subproject
     * @param relationship the nature of the dependency
     */
    public Dependency(String subproject, String relationship) {
        this(subproject, relationship, null);
    }

    /**
     * Whether this edge constrains workspace build order. Every
     * relationship does except {@code bundle}, whose artifact is
     * repository-resolved at package time and therefore orders nothing
     * (IKE-Network/ike-issues#963).
     *
     * @return true when the edge participates in topological sort and
     *         cycle detection
     */
    public boolean ordersBuild() {
        return !"bundle".equalsIgnoreCase(relationship);
    }
}
