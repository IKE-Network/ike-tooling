package network.ike.workspace.cascade;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The assembled IKE release cascade graph — the full cross-repo
 * release ordering, stitched from the per-project
 * {@code release-cascade.yaml} manifests by {@link CascadeAssembler}
 * (IKE-Network/ike-issues#402, #420).
 *
 * <p>No project authors this ordering. Each project version-controls
 * only its own {@code upstream}/{@code downstream} edges; the
 * assembler traverses those edges and topologically orders the
 * result. The {@link #repos()} list is that order: a node always
 * follows every node it consumes.
 *
 * <p>Helper methods answer the questions the release goals ask:
 * <ul>
 *   <li>{@link #downstreamOf(String)} — "I just released this
 *       groupId; what is now stale?"</li>
 *   <li>{@link #find(String)} / {@link #findByCoordinates(String,
 *       String)} — resolve a cascade node from a project's own POM
 *       coordinates.</li>
 * </ul>
 *
 * @param repos cascade nodes in topological order; never {@code null}
 */
public record ReleaseCascade(List<CascadeRepo> repos) {

    /**
     * Canonical constructor — defensively copies {@code repos} and
     * substitutes an empty list for {@code null}.
     */
    public ReleaseCascade {
        repos = repos == null ? List.of() : List.copyOf(repos);
    }

    /**
     * Looks up a cascade node by Maven {@code groupId}.
     *
     * @param groupId the groupId to find
     * @return the matching node, or empty if {@code groupId} is not a
     *         cascade member
     */
    public Optional<CascadeRepo> find(String groupId) {
        return repos.stream()
                .filter(r -> r.groupId().equals(groupId))
                .findFirst();
    }

    /**
     * Looks up a cascade node by exact {@code groupId} +
     * {@code artifactId} coordinates.
     *
     * @param groupId    the project's groupId
     * @param artifactId the project's artifactId
     * @return the matching node, or empty if the coordinates are not a
     *         cascade member
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
     * Returns the cascade members reachable downstream of
     * {@code groupId}, in cascade (topological) order.
     *
     * <p>These are exactly the repos that go stale when
     * {@code groupId} is released: each one consumes, directly or
     * through an intermediate, its artifacts. The traversal follows
     * the {@code downstream} edges of each node.
     *
     * @param groupId the groupId that was (or will be) released
     * @return downstream nodes in release order; empty if
     *         {@code groupId} has no consumers or is not a member
     */
    public List<CascadeRepo> downstreamOf(String groupId) {
        Set<String> stale = new LinkedHashSet<>();
        Deque<String> frontier = new ArrayDeque<>();
        frontier.add(groupId);
        while (!frontier.isEmpty()) {
            String current = frontier.poll();
            find(current).ifPresent(node -> {
                for (CascadeEdge edge : node.downstream()) {
                    if (stale.add(edge.groupId())) {
                        frontier.add(edge.groupId());
                    }
                }
            });
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
