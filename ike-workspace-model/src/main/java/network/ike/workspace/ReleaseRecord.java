package network.ike.workspace;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One release cycle of a working set — the typed model behind
 * {@code releases/release-<cycle>.yaml} in the workspace root
 * (IKE-Network/ike-issues#973; shape settled 2026-08-10: one file per
 * cycle, one row per released member, finalized by the cycle's
 * workspace-root release).
 *
 * <p>{@code ws:record-release} appends a {@link MemberRelease} row each
 * time a member releases; together with the member's manifest state
 * transition (see
 * {@link ManifestWriter#recordReleaseAlignment(String, String, String, String)})
 * the two writes are the same manifest evolution: the record says what
 * released, the state entry makes alignment and the release cascade obey
 * it.
 *
 * <p>Instances are immutable; {@link #withMember(String, MemberRelease)}
 * returns an updated copy. Member insertion order is preserved — the
 * record file reads in release order.
 *
 * @param cycle   the cycle label, e.g. {@code komet-wsr-1}; never blank
 * @param started when the cycle opened (opaque caller-supplied text,
 *                typically an ISO date); null when unknown
 * @param members released members in release order, keyed by subproject
 *                name; never null, possibly empty
 */
public record ReleaseRecord(
        String cycle,
        String started,
        Map<String, MemberRelease> members) {

    /**
     * One member's release within a cycle.
     *
     * @param version  the released (de-qualified) version, e.g.
     *                 {@code 3.0.7}; never blank
     * @param tag      the git release tag, e.g. {@code v3.0.7}; never blank
     * @param sha      the commit the tag points at; never blank
     * @param recorded when the row was written (opaque caller-supplied
     *                 text, typically an ISO date); null when unknown
     */
    public record MemberRelease(String version, String tag, String sha,
                                String recorded) {

        /**
         * Validate the row: version, tag, and sha are required.
         *
         * @throws ManifestException if version, tag, or sha is null or blank
         */
        public MemberRelease {
            requireNonBlank(version, "version");
            requireNonBlank(tag, "tag");
            requireNonBlank(sha, "sha");
        }
    }

    /**
     * Validate and defensively copy: the cycle label is required and the
     * member map is copied into an unmodifiable insertion-ordered map.
     *
     * @throws ManifestException if {@code cycle} is null or blank, or
     *                           {@code members} is null
     */
    public ReleaseRecord {
        requireNonBlank(cycle, "cycle");
        if (members == null) {
            throw new ManifestException("members must not be null");
        }
        members = Collections.unmodifiableMap(new LinkedHashMap<>(members));
    }

    /**
     * Open a new, empty cycle record.
     *
     * @param cycle   the cycle label, e.g. {@code komet-wsr-1}
     * @param started when the cycle opened (opaque caller-supplied text);
     *                may be null
     * @return an empty record for the cycle
     * @throws ManifestException if {@code cycle} is null or blank
     */
    public static ReleaseRecord start(String cycle, String started) {
        return new ReleaseRecord(cycle, started, new LinkedHashMap<>());
    }

    /**
     * Return a copy of this record with the given member's row added, or
     * replaced when the member already has one (a member re-released
     * within the cycle keeps a single row carrying the latest release).
     * A replaced member keeps its original position; a new member appends.
     *
     * @param name    the subproject name; never blank
     * @param release the member's release row
     * @return a new record including the row
     * @throws ManifestException if {@code name} is null or blank or
     *                           {@code release} is null
     */
    public ReleaseRecord withMember(String name, MemberRelease release) {
        requireNonBlank(name, "name");
        if (release == null) {
            throw new ManifestException("release must not be null");
        }
        Map<String, MemberRelease> updated = new LinkedHashMap<>(members);
        updated.put(name, release);
        return new ReleaseRecord(cycle, started, updated);
    }

    /**
     * Reject a null or blank argument with a uniform message.
     *
     * @param value the argument value to check
     * @param name  the argument name for the error message
     * @throws ManifestException if {@code value} is null or blank
     */
    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new ManifestException(name + " must not be null or blank");
        }
    }
}
