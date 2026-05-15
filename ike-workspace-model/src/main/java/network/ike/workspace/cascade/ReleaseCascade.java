package network.ike.workspace.cascade;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The parsed {@code release-cascade.yaml} manifest — the declarative
 * cross-repo release ordering for the IKE foundation
 * (IKE-Network/ike-issues#402).
 *
 * <p>The {@link #repos()} list is in topological order. Entries are
 * keyed off their Maven {@code groupId}. Helper methods answer the
 * questions the release goals ask:
 * <ul>
 *   <li>{@link #downstreamOf(String)} — "I just released this
 *       groupId; what is now stale?" — used by the
 *       {@code ike:release-*} cascade messages.</li>
 *   <li>{@link #find(String)} / {@link #findByCoordinates(String,
 *       String)} — resolve a cascade member from a project's own
 *       POM coordinates.</li>
 * </ul>
 *
 * @param standardsVersion the {@code ike-build-standards} version
 *                         that shipped this manifest (filtered in at
 *                         assembly time); informational only
 * @param repos            cascade entries in topological order;
 *                         never {@code null}
 */
public record ReleaseCascade(String standardsVersion,
                              List<CascadeRepo> repos) {

    /**
     * Canonical constructor — defensively copies {@code repos} and
     * substitutes an empty list for {@code null}.
     */
    public ReleaseCascade {
        repos = repos == null ? List.of() : List.copyOf(repos);
    }

    /**
     * Looks up a cascade entry by Maven {@code groupId}.
     *
     * @param groupId the groupId to find
     * @return the matching entry, or empty if {@code groupId} is not
     *         a cascade member
     */
    public Optional<CascadeRepo> find(String groupId) {
        return repos.stream()
                .filter(r -> r.groupId().equals(groupId))
                .findFirst();
    }

    /**
     * Looks up a cascade entry by exact {@code groupId} +
     * {@code artifactId} coordinates.
     *
     * @param groupId    the project's groupId
     * @param artifactId the project's artifactId
     * @return the matching entry, or empty if the coordinates are not
     *         a cascade member
     */
    public Optional<CascadeRepo> findByCoordinates(String groupId,
                                                   String artifactId) {
        return repos.stream()
                .filter(r -> r.groupId().equals(groupId)
                        && r.artifactId().equals(artifactId))
                .findFirst();
    }

    /**
     * Tests whether a {@code groupId} participates in the cascade.
     *
     * @param groupId the groupId
     * @return true if {@code groupId} is a cascade member
     */
    public boolean contains(String groupId) {
        return find(groupId).isPresent();
    }

    /**
     * Returns the cascade members that transitively consume
     * {@code groupId}, in cascade (topological) order.
     *
     * <p>These are exactly the repos that go stale when
     * {@code groupId} is released: each one depends, directly or
     * through an intermediate, on its artifacts.
     *
     * @param groupId the groupId that was (or will be) released
     * @return downstream entries in release order; empty if
     *         {@code groupId} has no consumers or is not a member
     */
    public List<CascadeRepo> downstreamOf(String groupId) {
        Set<String> stale = new LinkedHashSet<>();
        Deque<String> frontier = new ArrayDeque<>();
        frontier.add(groupId);
        while (!frontier.isEmpty()) {
            String current = frontier.poll();
            for (CascadeRepo candidate : repos) {
                if (candidate.consumes().contains(current)
                        && stale.add(candidate.groupId())) {
                    frontier.add(candidate.groupId());
                }
            }
        }
        List<CascadeRepo> ordered = new ArrayList<>();
        for (CascadeRepo r : repos) {
            if (stale.contains(r.groupId())) {
                ordered.add(r);
            }
        }
        return ordered;
    }
}
