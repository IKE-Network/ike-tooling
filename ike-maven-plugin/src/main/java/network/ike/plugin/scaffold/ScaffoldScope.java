package network.ike.plugin.scaffold;

import java.util.Locale;

/**
 * Scope a scaffold manifest entry targets.
 *
 * <p>A scaffold invocation dispatches entries based on scope:
 *
 * <ul>
 *   <li>{@link #PROJECT} entries are processed when {@code scaffold}
 *       runs inside a Maven project (i.e.
 *       {@code {project.root}/.ike/scaffold.lock} is the applicable
 *       lockfile).</li>
 *   <li>{@link #USER} entries are processed in every scaffold run,
 *       whether invoked inside a project or standalone
 *       (fresh-machine bootstrap), and track state in
 *       {@code {user.home}/.ike/scaffold.lock}.</li>
 * </ul>
 */
public enum ScaffoldScope {

    /**
     * Target lives under the current project root. Examples:
     * {@code mvnw}, {@code .mvn/maven.config}, {@code .gitignore}.
     */
    PROJECT("project"),

    /**
     * Target lives under the user's home directory. Examples:
     * {@code ~/.m2/settings.xml}, {@code ~/.git-hooks/post-checkout}.
     */
    USER("user");

    private final String manifestValue;

    ScaffoldScope(String manifestValue) {
        this.manifestValue = manifestValue;
    }

    /**
     * The kebab-case spelling used in manifest files.
     *
     * @return the manifest spelling of this scope
     */
    public String manifestValue() {
        return manifestValue;
    }

    /**
     * Parse a scope from its manifest spelling.
     *
     * @param value the manifest spelling (case-insensitive)
     * @return the matching scope
     * @throws IllegalArgumentException if no scope has this spelling
     */
    public static ScaffoldScope fromManifestValue(String value) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "scope value cannot be null");
        }
        String normalised = value.trim().toLowerCase(Locale.ROOT);
        for (ScaffoldScope s : values()) {
            if (s.manifestValue.equals(normalised)) {
                return s;
            }
        }
        throw new IllegalArgumentException(
                "Unknown scaffold scope: '" + value
                        + "'. Expected 'project' or 'user'.");
    }
}
