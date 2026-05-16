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
     * Assembles the cascade graph rooted at one known project.
     *
     * @param start        an edge identifying the starting project —
     *                     its coordinates and locators ({@code groupId},
     *                     {@code artifactId}, {@code repo},
     *                     {@code url}); {@code versionProperty} is
     *                     unused and may be {@code null}
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
        Map<String, CascadeRepo> byGroupId = new LinkedHashMap<>();
        Deque<CascadeRepo> frontier = new ArrayDeque<>();

        CascadeRepo startNode = node(start, startCascade);
        byGroupId.put(startNode.groupId(), startNode);
        frontier.add(startNode);

        while (!frontier.isEmpty()) {
            CascadeRepo current = frontier.poll();
            for (CascadeEdge edge : neighbourEdges(current)) {
                if (byGroupId.containsKey(edge.groupId())) {
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
                CascadeRepo neighbour = node(edge, resolved);
                byGroupId.put(neighbour.groupId(), neighbour);
                frontier.add(neighbour);
            }
        }

        verifyReciprocity(byGroupId);
        return new ReleaseCascade(topologicalOrder(byGroupId));
    }

    private static CascadeRepo node(CascadeEdge identity,
                                    ProjectCascade cascade) {
        return new CascadeRepo(identity.groupId(), identity.artifactId(),
                identity.repo(), identity.url(), cascade);
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
            Map<String, CascadeRepo> byGroupId) {
        for (CascadeRepo node : byGroupId.values()) {
            for (CascadeEdge down : node.downstream()) {
                CascadeRepo target = byGroupId.get(down.groupId());
                if (target == null || target.upstream().stream()
                        .noneMatch(u -> u.groupId()
                                .equals(node.groupId()))) {
                    throw new IllegalArgumentException(
                            "one-sided cascade edge: " + node.ga()
                            + " lists " + down.ga() + " as downstream,"
                            + " but " + down.ga() + " does not list "
                            + node.ga() + " as upstream");
                }
            }
            for (CascadeEdge up : node.upstream()) {
                CascadeRepo source = byGroupId.get(up.groupId());
                if (source == null || source.downstream().stream()
                        .noneMatch(d -> d.groupId()
                                .equals(node.groupId()))) {
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
     * Ties break on groupId so the order is deterministic.
     */
    private static List<CascadeRepo> topologicalOrder(
            Map<String, CascadeRepo> byGroupId) {
        Map<String, Integer> inDegree = new HashMap<>();
        for (CascadeRepo node : byGroupId.values()) {
            inDegree.put(node.groupId(), node.upstream().size());
        }
        TreeSet<String> ready = new TreeSet<>();
        inDegree.forEach((groupId, degree) -> {
            if (degree == 0) {
                ready.add(groupId);
            }
        });

        List<CascadeRepo> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            String groupId = ready.pollFirst();
            CascadeRepo node = byGroupId.get(groupId);
            order.add(node);
            for (CascadeEdge down : node.downstream()) {
                int remaining = inDegree.merge(
                        down.groupId(), -1, Integer::sum);
                if (remaining == 0) {
                    ready.add(down.groupId());
                }
            }
        }
        if (order.size() != byGroupId.size()) {
            throw new IllegalArgumentException(
                    "release cascade contains a cycle — no release"
                    + " order exists");
        }
        return order;
    }
}
