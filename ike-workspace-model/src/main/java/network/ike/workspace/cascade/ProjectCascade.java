package network.ike.workspace.cascade;

import java.util.List;

/**
 * One project's own {@code src/main/cascade/release-cascade.yaml} —
 * its edges in the IKE release cascade (IKE-Network/ike-issues#420).
 *
 * <p>The cascade is modelled as a loosely-coupled distributed system:
 * every project version-controls this file in its own git tree and
 * declares <em>only its own</em> {@code upstream} and {@code downstream}
 * edges. No project authors the global ordering; the full graph is
 * assembled by {@link CascadeAssembler} traversing these files.
 *
 * <p>The {@code head} and {@code terminal} markers are asserted, not
 * inferred. A project at the head of the cascade has no
 * {@code upstream} edge, and one at the end has no {@code downstream}
 * edge — but an <em>omitted</em> edge looks identical to a genuine
 * endpoint, so each endpoint must positively declare itself. The
 * canonical constructor rejects a marker that disagrees with the
 * corresponding edge list, turning a forgotten edge into a manifest
 * error rather than an invisible omission.
 *
 * @param schema     the manifest schema version (currently {@code 1})
 * @param head       {@code true} iff this project declares no
 *                   {@code upstream} edge — the cascade head
 * @param upstream   edges to the projects this one consumes;
 *                   the version property each edge pins is derived
 *                   from {@code G·A} via
 *                   {@link CascadeEdge#versionProperty()}; never
 *                   {@code null}
 * @param terminal   {@code true} iff this project declares no
 *                   {@code downstream} edge — the cascade terminus
 * @param downstream edges to the projects that consume this one;
 *                   never {@code null}
 */
public record ProjectCascade(int schema, boolean head,
                              List<CascadeEdge> upstream,
                              boolean terminal,
                              List<CascadeEdge> downstream) {

    /**
     * Canonical constructor — defensively copies the edge lists,
     * substitutes empty lists for {@code null}, and verifies the
     * {@code head}/{@code terminal} markers agree with the edge
     * lists. The per-edge {@code version-property} check that the
     * legacy YAML schema needed is gone: every edge derives its
     * canonical {@code G·A} version-property from its coordinates,
     * so it is always non-blank (IKE-Network/ike-issues#496).
     */
    public ProjectCascade {
        upstream = upstream == null ? List.of() : List.copyOf(upstream);
        downstream = downstream == null ? List.of()
                : List.copyOf(downstream);
        if (head != upstream.isEmpty()) {
            throw new IllegalArgumentException(head
                    ? "release-cascade.yaml declares 'head: true' but"
                      + " also lists upstream edges"
                    : "release-cascade.yaml has no upstream edges and"
                      + " must declare 'head: true' — a missing"
                      + " upstream edge must not be silently omitted");
        }
        if (terminal != downstream.isEmpty()) {
            throw new IllegalArgumentException(terminal
                    ? "release-cascade.yaml declares 'terminal: true'"
                      + " but also lists downstream edges"
                    : "release-cascade.yaml has no downstream edges and"
                      + " must declare 'terminal: true' — a missing"
                      + " downstream edge must not be silently omitted");
        }
    }
}
