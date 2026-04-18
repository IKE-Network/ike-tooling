package network.ike.workspace;

/**
 * An inter-repository dependency declared in a subproject's
 * {@code depends-on} list.
 *
 * @param subproject      the name of the depended-on subproject
 * @param relationship    the nature of the dependency ("build", "content", or "tool-time")
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
     * Two-arg constructor for backwards compatibility (no version-property).
     *
     * @param subproject   the name of the depended-on subproject
     * @param relationship the nature of the dependency
     */
    public Dependency(String subproject, String relationship) {
        this(subproject, relationship, null);
    }
}
