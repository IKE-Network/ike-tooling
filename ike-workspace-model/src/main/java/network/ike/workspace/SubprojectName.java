package network.ike.workspace;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Validated subproject-name value — the workspace.yaml key, the
 * subproject directory name, and the {@code <subproject>} reference
 * in aggregator POMs all share this string (ike-issues#295).
 *
 * <p>Validation matches {@link FeatureName}: ASCII letters, digits,
 * {@code -}, {@code _}, {@code .}; must start with a letter or digit.
 * Filesystem-safe and shell-metacharacter-safe by construction.
 *
 * <p>Single typed entry point so every consumer of a subproject-name
 * argument ({@code ws:add}, {@code ws:remove}, {@code ws:promote},
 * {@code ws:demote}, {@code ws:detach}, {@code ws:attach-*}, etc.)
 * gets identical validation rather than scattering regex literals
 * at call sites — per the compiler-visibility principle.
 */
public final class SubprojectName {

    /**
     * Syntactic validator. Anchored at both ends; rejects anything
     * not matching the documented rule set.
     */
    private static final Pattern VALID =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    private final String value;

    private SubprojectName(String value) {
        this.value = value;
    }

    /**
     * Validate {@code raw} and wrap it as a {@code SubprojectName}.
     *
     * @param raw the candidate name (typically from a
     *            {@code -Dsubproject=<name>} command-line argument
     *            or a {@code workspace.yaml} key)
     * @return a validated {@code SubprojectName}
     * @throws IllegalArgumentException if {@code raw} is null, empty,
     *                                  or violates any documented rule
     */
    public static SubprojectName of(String raw) {
        if (raw == null || raw.isEmpty()) {
            throw new IllegalArgumentException(
                    "Subproject name must be non-empty.");
        }
        if (!VALID.matcher(raw).matches()) {
            throw new IllegalArgumentException(
                    "Subproject name '" + raw + "' is not filesystem-safe. "
                            + "Allowed: ASCII letters, digits, '-', '_', '.'; "
                            + "must start with a letter or digit; no path "
                            + "separators, whitespace, or shell metacharacters.");
        }
        return new SubprojectName(raw);
    }

    /**
     * The validated subproject name as a string.
     *
     * @return the raw value (never null or empty)
     */
    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        return (o instanceof SubprojectName other) && value.equals(other.value);
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
