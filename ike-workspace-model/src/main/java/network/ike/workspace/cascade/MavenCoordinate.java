package network.ike.workspace.cascade;

import java.util.Optional;

/**
 * A Maven {@code groupId:artifactId} coordinate — the canonical
 * value type for "which artifact" across the cascade model.
 *
 * <p>Replaces the long-standing {@code (String groupId, String artifactId)}
 * idiom that the cascade code carried since the YAML-manifest era.
 * Treating the pair as a record gives the compiler what convention
 * could not: argument-order checking
 * ({@code resolve(artifactId, groupId)} no longer silently compiles),
 * single-parameter signatures wherever a coordinate was passed, and
 * a free {@link Object#equals(Object)} / {@link Object#hashCode()}
 * so {@code Map<MavenCoordinate, ...>} replaces the ad-hoc
 * {@code "groupId:artifactId"} string keys the assembler used to
 * build by hand.
 *
 * <p>Coordinates produce three derived strings that recur in the
 * cascade model:
 * <ul>
 *   <li>{@link #ga()} — the {@code "groupId:artifactId"} display form,
 *       used in log lines and as a parse/format target for human
 *       input.</li>
 *   <li>{@link #versionProperty()} — the canonical IKE version-property
 *       name {@code groupId·artifactId} (U+00B7 MIDDLE DOT). Used by
 *       the alignment path to locate the {@code ${G·A}} property that
 *       pins this coordinate. See IKE-Network/ike-issues#470.</li>
 *   <li>{@link #toString()} — equal to {@link #ga()}, for direct use
 *       in error messages and string concatenation.</li>
 * </ul>
 *
 * @param groupId    the Maven {@code groupId}; non-null and non-blank
 * @param artifactId the Maven {@code artifactId}; non-null and non-blank
 */
public record MavenCoordinate(String groupId, String artifactId)
        implements Comparable<MavenCoordinate> {

    /**
     * Canonical constructor — validates that both components are
     * non-null and non-blank.
     */
    public MavenCoordinate {
        if (groupId == null || groupId.isBlank()) {
            throw new IllegalArgumentException(
                    "MavenCoordinate requires a groupId");
        }
        if (artifactId == null || artifactId.isBlank()) {
            throw new IllegalArgumentException(
                    "MavenCoordinate requires an artifactId");
        }
    }

    /**
     * Builds a coordinate or throws. Sugar for
     * {@link #MavenCoordinate(String, String)}.
     *
     * @param groupId    the Maven {@code groupId}
     * @param artifactId the Maven {@code artifactId}
     * @return the coordinate
     */
    public static MavenCoordinate of(String groupId, String artifactId) {
        return new MavenCoordinate(groupId, artifactId);
    }

    /**
     * Builds a coordinate, or returns empty when either component is
     * null or blank. The lenient companion to {@link #of}, useful
     * when scanning Maven models whose {@code <plugin>} or
     * {@code <dependency>} entries may legitimately omit
     * {@code <groupId>} (the Maven default plugin group, for
     * instance) — the deriver does not want to throw on these, it
     * wants to skip them.
     *
     * @param groupId    the Maven {@code groupId}; may be {@code null}
     *                   or blank
     * @param artifactId the Maven {@code artifactId}; may be
     *                   {@code null} or blank
     * @return the coordinate, or empty when either component is
     *         missing
     */
    public static Optional<MavenCoordinate> tryOf(String groupId,
                                                   String artifactId) {
        if (groupId == null || groupId.isBlank()
                || artifactId == null || artifactId.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new MavenCoordinate(groupId, artifactId));
    }

    /**
     * Parses a {@code "groupId:artifactId"} string into a coordinate.
     * Splits on the first colon, matching the Maven display
     * convention.
     *
     * @param ga the {@code G:A} string
     * @return the coordinate
     * @throws IllegalArgumentException if {@code ga} contains no
     *                                  colon or has an empty
     *                                  component
     */
    public static MavenCoordinate parse(String ga) {
        if (ga == null) {
            throw new IllegalArgumentException("ga string is required");
        }
        int colon = ga.indexOf(':');
        if (colon < 0) {
            throw new IllegalArgumentException(
                    "not a 'groupId:artifactId' coordinate: " + ga);
        }
        return new MavenCoordinate(
                ga.substring(0, colon), ga.substring(colon + 1));
    }

    /**
     * Returns the {@code "groupId:artifactId"} display form.
     *
     * @return the {@code G:A} string
     */
    public String ga() {
        return groupId + ":" + artifactId;
    }

    /**
     * Returns the canonical IKE version-property name —
     * {@code groupId·artifactId} (U+00B7 MIDDLE DOT). The
     * alignment path uses this to locate the {@code ${G·A}}
     * property that pins this coordinate.
     *
     * @return the canonical property name
     */
    public String versionProperty() {
        return groupId + "·" + artifactId;
    }

    /**
     * Returns {@link #ga()}.
     *
     * @return the {@code G:A} string
     */
    @Override
    public String toString() {
        return ga();
    }

    /**
     * Natural ordering: by {@code groupId} first, then by
     * {@code artifactId}. Lets {@code TreeMap} / {@code TreeSet}
     * use coordinates directly and produces deterministic cascade
     * orderings.
     *
     * @param other the coordinate to compare against
     * @return negative / zero / positive per the standard contract
     */
    @Override
    public int compareTo(MavenCoordinate other) {
        int byGroup = groupId.compareTo(other.groupId);
        return byGroup != 0
                ? byGroup
                : artifactId.compareTo(other.artifactId);
    }
}
