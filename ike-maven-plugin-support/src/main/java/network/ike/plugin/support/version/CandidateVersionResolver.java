package network.ike.plugin.support.version;

import java.util.List;

/**
 * Resolves the available released versions of a Maven coordinate.
 *
 * <p>Pure interface so {@code FoundationBaker} can be unit tested
 * with a fake (no Maven session, no network). The
 * {@link SessionCandidateVersionResolver} implementation queries the
 * Maven 4 {@code VersionRangeResolver} service against the configured
 * remote and local repositories.
 *
 * <p>Implementations MUST:
 * <ul>
 *   <li>Filter out SNAPSHOT versions — foundation resolution only
 *       ever proposes released versions.</li>
 *   <li>Return versions in ascending order, with the highest at the
 *       end of the list.</li>
 *   <li>Return an empty list (not null) when nothing is available or
 *       the coordinate cannot be resolved.</li>
 * </ul>
 */
public interface CandidateVersionResolver {

    /**
     * Look up all known released versions of {@code groupId:artifactId}.
     *
     * <p>Implementations may use {@code currentVersion} as a hint —
     * e.g. to construct a Maven version range like
     * {@code "(currentVersion,)"} that only returns strictly newer
     * versions. Callers must not depend on that filtering: they should
     * still compare returned versions against the current version
     * themselves.
     *
     * @param groupId        the coordinate's groupId
     * @param artifactId     the coordinate's artifactId
     * @param currentVersion the currently-declared version, used as a
     *                       hint for range-based resolvers; may be null
     * @return ascending list of released versions; empty if none
     */
    List<String> resolveCandidates(String groupId, String artifactId,
                                   String currentVersion);

    /**
     * Convenience: return the highest released candidate strictly newer
     * than {@code currentVersion}, or null if there isn't one. Compares
     * versions with {@link MavenVersionComparator#INSTANCE}.
     *
     * @param groupId        the coordinate's groupId
     * @param artifactId     the coordinate's artifactId
     * @param currentVersion the currently-declared version
     * @return the highest strictly-newer candidate, or null
     */
    default String resolveHighestCandidate(String groupId, String artifactId,
                                           String currentVersion) {
        List<String> candidates = resolveCandidates(groupId, artifactId,
                currentVersion);
        String best = null;
        for (String candidate : candidates) {
            if (currentVersion != null
                    && MavenVersionComparator.INSTANCE.compare(
                            candidate, currentVersion) <= 0) {
                continue;
            }
            if (best == null
                    || MavenVersionComparator.INSTANCE.compare(
                            candidate, best) > 0) {
                best = candidate;
            }
        }
        return best;
    }
}
