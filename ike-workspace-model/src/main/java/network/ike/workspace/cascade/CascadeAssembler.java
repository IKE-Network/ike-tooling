package network.ike.workspace.cascade;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Assembles the full IKE release cascade graph by traversing the
 * per-project {@code release-cascade.yaml} manifests
 * (IKE-Network/ike-issues#420).
 *
 * <p>The cascade is a loosely-coupled distributed system: each
 * project version-controls only its own {@code upstream}/{@code downstream}
 * edges. This assembler starts from one project, follows every edge
 * to its neighbours (resolving each neighbour's manifest through a
 * caller-supplied {@link CascadeResolver}), and stitches the result
 * into a single topologically ordered {@link ReleaseCascade}.
 *
 * <p>Two consistency rules are enforced during assembly:
 * <ul>
 *   <li><b>Edge reciprocity</b> — if project A names B as
 *       {@code downstream}, B must name A as {@code upstream}, and
 *       vice versa. A one-sided edge is a manifest error.</li>
 *   <li><b>Acyclicity</b> — the consume relation must be a DAG;
 *       a cycle has no release order.</li>
 * </ul>
 */
public final class CascadeAssembler {

    /**
     * Resolves the {@link ProjectCascade} for a cascade neighbour.
     *
     * <p>The caller decides how a neighbour is reached — a sibling
     * directory on disk, or a git checkout from the edge's
     * {@code url} — and reads its
     * {@code src/main/cascade/release-cascade.yaml}.
     */
    @FunctionalInterface
    public interface CascadeResolver {

        /**
         * Resolves and parses one neighbour's manifest.
         *
         * @param edge the edge naming the neighbour (carries its
         *             coordinates, {@code repo}, and {@code url})
         * @return the neighbour's parsed manifest
         * @throws RuntimeException if the neighbour's manifest cannot
         *                          be located or parsed
         */
        ProjectCascade resolve(CascadeEdge edge);
    }

    private CascadeAssembler() {}

    /**
     * Assembles the cascade graph rooted at one known project,
     * without populating {@link RepositoryKey} on the nodes.
     *
     * @param start        an edge identifying the starting project —
     *                     its coordinates and locators ({@code groupId},
     *                     {@code artifactId}, {@code repo},
     *                     {@code url}); {@code kind} is unused
     * @param startCascade the starting project's already-parsed
     *                     manifest
     * @param resolver     resolves every other project's manifest
     * @return the assembled, topologically ordered cascade
     * @throws IllegalArgumentException if an edge is one-sided or the
     *                                  graph contains a cycle
     */
    public static ReleaseCascade assemble(CascadeEdge start,
                                          ProjectCascade startCascade,
                                          CascadeResolver resolver) {
        return assemble(start, startCascade, resolver, null);
    }

    /**
     * Assembles the cascade graph rooted at one known project,
     * populating each node's {@link RepositoryKey} via
     * {@code repositoryResolver} when supplied
     * (IKE-Network/ike-issues#496 part C).
     *
     * @param start              an identifying edge for the starting
     *                           project
     * @param startCascade       the starting project's parsed manifest
     * @param resolver           resolves every other project's manifest
     * @param repositoryResolver maps a coordinate to its
     *                           {@link RepositoryKey}; may be
     *                           {@code null} to leave keys unset on
     *                           the assembled nodes
     * @return the assembled, topologically ordered cascade
     * @throws IllegalArgumentException if an edge is one-sided or the
     *                                  graph contains a cycle
     */
    public static ReleaseCascade assemble(CascadeEdge start,
                                          ProjectCascade startCascade,
                                          CascadeResolver resolver,
                                          RepositoryKeyResolver repositoryResolver) {
        // Identity is the MavenCoordinate (groupId + artifactId), NOT
        // groupId alone — two foundation members can share a groupId.
        // For example, `network.ike.tooling:ike-tooling` and
        // `network.ike.tooling:ike-workspace-extension` both live
        // under the `network.ike.tooling` group, but they are
        // independent cascade heads with different downstream edges.
        // Keying any of these maps by groupId would collide the two
        // nodes and silently drop edges, which used to surface as a
        // false-positive cycle (IKE-Network/ike-issues#466).
        //
        // Once #496 part D lands, identity collapses further onto the
        // RepositoryKey populated by repositoryResolver — multiple
        // coordinates that resolve to one repository become one node
        // — but until then MavenCoordinate remains the assembler's
        // working key.
        Map<MavenCoordinate, CascadeRepo> byCoordinate =
                new LinkedHashMap<>();
        Deque<CascadeRepo> frontier = new ArrayDeque<>();

        CascadeRepo startNode = node(start, startCascade,
                repositoryResolver);
        byCoordinate.put(startNode.coordinate(), startNode);
        frontier.add(startNode);

        while (!frontier.isEmpty()) {
            CascadeRepo current = frontier.poll();
            for (CascadeEdge edge : neighbourEdges(current)) {
                if (byCoordinate.containsKey(edge.coordinate())) {
                    continue;
                }
                ProjectCascade resolved;
                try {
                    resolved = resolver.resolve(edge);
                } catch (RuntimeException e) {
                    throw new IllegalArgumentException(
                            "cannot resolve release-cascade.yaml for"
                            + " cascade member " + edge.ga() + ": "
                            + e.getMessage(), e);
                }
                if (resolved == null) {
                    throw new IllegalArgumentException(
                            "cannot resolve release-cascade.yaml for"
                            + " cascade member " + edge.ga());
                }
                CascadeRepo neighbour = node(edge, resolved,
                        repositoryResolver);
                byCoordinate.put(neighbour.coordinate(), neighbour);
                frontier.add(neighbour);
            }
        }

        verifyReciprocity(byCoordinate);
        return new ReleaseCascade(topologicalOrder(byCoordinate));
    }

    private static CascadeRepo node(CascadeEdge identity,
                                    ProjectCascade cascade,
                                    RepositoryKeyResolver repositoryResolver) {
        RepositoryKey key = repositoryResolver == null
                ? null
                : repositoryResolver.resolve(identity.coordinate())
                        .orElse(null);
        return new CascadeRepo(identity.coordinate(), identity.repo(),
                identity.url(), key, cascade);
    }

    private static List<CascadeEdge> neighbourEdges(CascadeRepo node) {
        List<CascadeEdge> edges = new ArrayList<>(node.upstream());
        edges.addAll(node.downstream());
        return edges;
    }

    /**
     * Verifies every edge is matched by a reciprocal edge on the
     * neighbour: A→B {@code downstream} ⇔ B→A {@code upstream}.
     */
    private static void verifyReciprocity(
            Map<MavenCoordinate, CascadeRepo> byCoordinate) {
        for (CascadeRepo node : byCoordinate.values()) {
            MavenCoordinate self = node.coordinate();
            for (CascadeEdge down : node.downstream()) {
                CascadeRepo target = byCoordinate.get(down.coordinate());
                if (target == null || target.upstream().stream()
                        .map(CascadeEdge::coordinate)
                        .noneMatch(self::equals)) {
                    throw new IllegalArgumentException(
                            "one-sided cascade edge: " + self
                            + " lists " + down.ga() + " as downstream,"
                            + " but " + down.ga() + " does not list "
                            + self + " as upstream");
                }
            }
            for (CascadeEdge up : node.upstream()) {
                CascadeRepo source = byCoordinate.get(up.coordinate());
                if (source == null || source.downstream().stream()
                        .map(CascadeEdge::coordinate)
                        .noneMatch(self::equals)) {
                    throw new IllegalArgumentException(
                            "one-sided cascade edge: " + self
                            + " lists " + up.ga() + " as upstream,"
                            + " but " + up.ga() + " does not list "
                            + self + " as downstream");
                }
            }
        }
    }

    /**
     * Kahn topological sort — a node follows every node it consumes.
     * Ties break on the natural ordering of {@link MavenCoordinate}
     * so the order is deterministic.
     */
    private static List<CascadeRepo> topologicalOrder(
            Map<MavenCoordinate, CascadeRepo> byCoordinate) {
        Map<MavenCoordinate, Integer> inDegree = new HashMap<>();
        for (CascadeRepo node : byCoordinate.values()) {
            inDegree.put(node.coordinate(), node.upstream().size());
        }
        TreeSet<MavenCoordinate> ready = new TreeSet<>();
        inDegree.forEach((coordinate, degree) -> {
            if (degree == 0) {
                ready.add(coordinate);
            }
        });

        List<CascadeRepo> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            MavenCoordinate coord = ready.pollFirst();
            CascadeRepo node = byCoordinate.get(coord);
            order.add(node);
            for (CascadeEdge down : node.downstream()) {
                int remaining = inDegree.merge(
                        down.coordinate(), -1, Integer::sum);
                if (remaining == 0) {
                    ready.add(down.coordinate());
                }
            }
        }
        if (order.size() != byCoordinate.size()) {
            throw new IllegalArgumentException(
                    "release cascade contains a cycle — no release"
                    + " order exists");
        }
        return order;
    }
}
