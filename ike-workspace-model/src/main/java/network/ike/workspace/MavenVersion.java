package network.ike.workspace;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Validated Maven version value — the {@code <version>} string for
 * any Maven coordinate, including workspace-root, subproject, and
 * parent versions (ike-issues#295).
 *
 * <p>Per {@code feedback_no_semver_assumption}, this type does NOT
 * enforce semver. IKE versions are most commonly single-segment
 * monotonic ({@code 1}, {@code 133}, {@code 133-SNAPSHOT}); some
 * downstream artifacts use semver-like ({@code 1.0.0-SNAPSHOT},
 * {@code 1.127.2-feature-x-SNAPSHOT}); calendar-based versions
 * ({@code 20240315-SNAPSHOT}) are also valid. The validator accepts
 * any of these.
 *
 * <p>Validation rules:
 * <ul>
 *   <li>Non-empty</li>
 *   <li>Starts with a letter or digit</li>
 *   <li>Allowed characters: ASCII letters, digits,
 *       {@code .}, {@code -}, {@code _}, {@code +}</li>
 *   <li>No whitespace or shell-metacharacter hazards
 *       ({@code <}, {@code >}, {@code "}, {@code '}, {@code $},
 *       backtick, etc.) — those would be dangerous to interpolate
 *       into a POM.</li>
 * </ul>
 *
 * <p>Single typed entry point so every consumer of a version
 * argument ({@code ws:create -Dversion=…},
 * {@code ws:adopt-root -Dversion=…},
 * {@code ws:set-parent -Dparent.version=…},
 * {@code ws:release -DreleaseVersion=…},
 * {@code ws:post-release -DnextVersion=…}) gets identical validation.
 */
public final class MavenVersion {

    /**
     * Syntactic validator. Permissive enough to accept every Maven
     * version style the IKE ecosystem uses (single-segment monotonic,
     * semver, calendar, branch-qualified) while excluding XML and
     * shell hazards.
     */
    private static final Pattern VALID =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]*");

    private final String value;

    private MavenVersion(String value) {
        this.value = value;
    }

    /**
     * Validate {@code raw} and wrap it as a {@code MavenVersion}.
     *
     * @param raw the candidate version string (typically from a
     *            {@code -Dversion=…} or {@code -D<x>.version=…}
     *            command-line argument, or a {@code workspace.yaml}
     *            field)
     * @return a validated {@code MavenVersion}
     * @throws IllegalArgumentException if {@code raw} is null, empty,
     *                                  or violates any documented rule
     */
    public static MavenVersion of(String raw) {
        if (raw == null || raw.isEmpty()) {
            throw new IllegalArgumentException(
                    "Maven version must be non-empty.");
        }
        if (!VALID.matcher(raw).matches()) {
            throw new IllegalArgumentException(
                    "Maven version '" + raw + "' contains invalid characters. "
                            + "Allowed: ASCII letters, digits, '.', '-', '_', '+'; "
                            + "must start with a letter or digit; no whitespace "
                            + "or shell metacharacters.");
        }
        return new MavenVersion(raw);
    }

    /**
     * The validated version as a string.
     *
     * @return the raw value (never null or empty)
     */
    public String value() {
        return value;
    }

    /**
     * Whether this version ends in {@code -SNAPSHOT} — the standard
     * Maven SNAPSHOT marker.
     *
     * @return true iff the version is a SNAPSHOT
     */
    public boolean isSnapshot() {
        return value.endsWith("-SNAPSHOT");
    }

    @Override
    public boolean equals(Object o) {
        return (o instanceof MavenVersion other) && value.equals(other.value);
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
