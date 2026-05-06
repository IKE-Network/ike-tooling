package network.ike.workspace;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Validated feature-name value — the {@code <feature>} portion of a
 * branch name like {@code feature/<feature>} and the suffix portion
 * of a sibling-clone directory name like
 * {@code <workspace>-<feature>} (ike-issues#201, ike-issues#205).
 *
 * <p>Centralizes the regex enforcing "filesystem-safe" so the
 * compiler can police a single typed entry point rather than
 * scattered string templates at call sites
 * (per the compiler-visibility principle).
 *
 * <p>Validation rules — syntactic only:
 * <ul>
 *   <li>Non-empty</li>
 *   <li>No path separators ({@code /}, {@code \})</li>
 *   <li>No whitespace</li>
 *   <li>No shell-metacharacter hazards
 *       ({@code *}, {@code ?}, {@code [}, {@code ]}, {@code "},
 *       {@code '}, {@code $}, backtick)</li>
 *   <li>ASCII letters, digits, {@code -}, {@code _}, {@code .};
 *       must start with a letter or digit</li>
 * </ul>
 *
 * <p>Uniqueness checks (does a sibling directory already exist?) are
 * intentionally <em>not</em> in this class — those depend on the
 * caller's filesystem context and live in the calling Mojo.
 */
public final class FeatureName {

    /**
     * Syntactic validator. Anchored at both ends; rejects anything
     * not matching the documented rule set.
     */
    private static final Pattern VALID =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    private final String value;

    private FeatureName(String value) {
        this.value = value;
    }

    /**
     * Validate {@code raw} and wrap it as a {@code FeatureName}.
     *
     * @param raw the candidate feature name (typically from a
     *            {@code -Dfeature=<name>} command-line argument)
     * @return a validated {@code FeatureName}
     * @throws IllegalArgumentException if {@code raw} is null, empty,
     *                                  or violates any documented rule
     */
    public static FeatureName of(String raw) {
        if (raw == null || raw.isEmpty()) {
            throw new IllegalArgumentException(
                    "Feature name must be non-empty.");
        }
        if (!VALID.matcher(raw).matches()) {
            throw new IllegalArgumentException(
                    "Feature name '" + raw + "' is not filesystem-safe. "
                            + "Allowed: ASCII letters, digits, '-', '_', '.'; "
                            + "must start with a letter or digit; no path "
                            + "separators, whitespace, or shell metacharacters.");
        }
        return new FeatureName(raw);
    }

    /**
     * The validated feature name as a string.
     *
     * @return the raw value (never null or empty)
     */
    public String value() {
        return value;
    }

    /**
     * Compose the sibling-clone directory name for this feature inside
     * the given primary workspace.
     *
     * <p>For {@code primaryWorkspaceName="ike-komet-ws"} and feature
     * {@code "reasoner"}, returns {@code "ike-komet-ws-reasoner"}.
     *
     * <p>This is the single approved place to construct sibling
     * directory names — call sites must not concatenate strings
     * directly (ike-issues#205).
     *
     * @param primaryWorkspaceName the primary workspace's directory
     *                             name; must be non-null and non-empty
     * @return {@code primaryWorkspaceName + "-" + value()}
     * @throws IllegalArgumentException if {@code primaryWorkspaceName}
     *                                  is null or empty
     */
    public String siblingDirectoryName(String primaryWorkspaceName) {
        if (primaryWorkspaceName == null || primaryWorkspaceName.isEmpty()) {
            throw new IllegalArgumentException(
                    "Primary workspace name must be non-empty.");
        }
        return primaryWorkspaceName + "-" + value;
    }

    @Override
    public boolean equals(Object o) {
        return (o instanceof FeatureName other) && value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
