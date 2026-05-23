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
        // Identity is groupId+artifactId (CascadeRepo.ga()), NOT
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
        // RepositoryKey populated by repositoryResolver — multiple GA
        // pairs that resolve to one repository become one node — but
        // until then GA remains the assembler's working key.
        Map<String, CascadeRepo> byGa = new LinkedHashMap<>();
        Deque<CascadeRepo> frontier = new ArrayDeque<>();

        CascadeRepo startNode = node(start, startCascade,
                repositoryResolver);
        byGa.put(startNode.ga(), startNode);
        frontier.add(startNode);

        while (!frontier.isEmpty()) {
            CascadeRepo current = frontier.poll();
            for (CascadeEdge edge : neighbourEdges(current)) {
                if (byGa.containsKey(edge.ga())) {
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
                byGa.put(neighbour.ga(), neighbour);
                frontier.add(neighbour);
            }
        }

        verifyReciprocity(byGa);
        return new ReleaseCascade(topologicalOrder(byGa));
    }

    private static CascadeRepo node(CascadeEdge identity,
                                    ProjectCascade cascade,
                                    RepositoryKeyResolver repositoryResolver) {
        RepositoryKey key = null;
        if (repositoryResolver != null) {
            key = repositoryResolver.resolve(identity.groupId(),
                    identity.artifactId()).orElse(null);
        }
        return new CascadeRepo(identity.groupId(), identity.artifactId(),
                identity.repo(), identity.url(), key, cascade);
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
            Map<String, CascadeRepo> byGa) {
        for (CascadeRepo node : byGa.values()) {
            for (CascadeEdge down : node.downstream()) {
                CascadeRepo target = byGa.get(down.ga());
                if (target == null || target.upstream().stream()
                        .noneMatch(u -> u.ga().equals(node.ga()))) {
                    throw new IllegalArgumentException(
                            "one-sided cascade edge: " + node.ga()
                            + " lists " + down.ga() + " as downstream,"
                            + " but " + down.ga() + " does not list "
                            + node.ga() + " as upstream");
                }
            }
            for (CascadeEdge up : node.upstream()) {
                CascadeRepo source = byGa.get(up.ga());
                if (source == null || source.downstream().stream()
                        .noneMatch(d -> d.ga().equals(node.ga()))) {
                    throw new IllegalArgumentException(
                            "one-sided cascade edge: " + node.ga()
                            + " lists " + up.ga() + " as upstream,"
                            + " but " + up.ga() + " does not list "
                            + node.ga() + " as downstream");
                }
            }
        }
    }

    /**
     * Kahn topological sort — a node follows every node it consumes.
     * Ties break on ga so the order is deterministic.
     */
    private static List<CascadeRepo> topologicalOrder(
            Map<String, CascadeRepo> byGa) {
        Map<String, Integer> inDegree = new HashMap<>();
        for (CascadeRepo node : byGa.values()) {
            inDegree.put(node.ga(), node.upstream().size());
        }
        TreeSet<String> ready = new TreeSet<>();
        inDegree.forEach((ga, degree) -> {
            if (degree == 0) {
                ready.add(ga);
            }
        });

        List<CascadeRepo> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            String ga = ready.pollFirst();
            CascadeRepo node = byGa.get(ga);
            order.add(node);
            for (CascadeEdge down : node.downstream()) {
                int remaining = inDegree.merge(
                        down.ga(), -1, Integer::sum);
                if (remaining == 0) {
                    ready.add(down.ga());
                }
            }
        }
        if (order.size() != byGa.size()) {
            throw new IllegalArgumentException(
                    "release cascade contains a cycle — no release"
                    + " order exists");
        }
        return order;
    }
}
