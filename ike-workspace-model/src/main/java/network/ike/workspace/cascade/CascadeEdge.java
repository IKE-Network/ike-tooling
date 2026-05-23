package network.ike.workspace.cascade;

/**
 * A directed edge in the IKE release cascade — a pointer from one
 * project to one of its cascade neighbours.
 *
 * <p>An edge carries the neighbour's reactor-root Maven coordinates,
 * an on-disk {@code repo} name, a git {@code url}, and an
 * {@link EdgeKind} naming the POM site the edge was derived from.
 * The version property the alignment step rewrites — once
 * {@code ${G·A}} naming is universal — is mechanical:
 * {@link #versionProperty()} returns {@code groupId + "·" + artifactId},
 * so no field stores it separately. The pre-derivation per-project
 * {@code release-cascade.yaml} manifests carried a {@code version-property}
 * value here; the derivation pipeline introduced by
 * IKE-Network/ike-issues#496 replaces it.
 *
 * @param groupId    the neighbour's reactor-root Maven {@code groupId};
 *                   the primary identity key
 * @param artifactId the neighbour's reactor-root Maven
 *                   {@code artifactId}
 * @param repo       the neighbour's on-disk directory / GitHub repo
 *                   name; defaults to {@code artifactId} when blank
 * @param url        the neighbour's canonical upstream git URL, or
 *                   {@code null} when unknown
 * @param kind       the Maven model site this edge was derived from;
 *                   defaults to {@link EdgeKind#DEPENDENCY} when
 *                   {@code null}
 */
public record CascadeEdge(String groupId, String artifactId,
                           String repo, String url,
                           EdgeKind kind) {

    /**
     * Canonical constructor — validates the identity coordinates,
     * defaults {@code repo} to {@code artifactId}, and defaults
     * {@code kind} to {@link EdgeKind#DEPENDENCY}.
     */
    public CascadeEdge {
        if (groupId == null || groupId.isBlank()) {
            throw new IllegalArgumentException(
                    "cascade edge requires a groupId");
        }
        if (artifactId == null || artifactId.isBlank()) {
            throw new IllegalArgumentException(
                    "cascade edge requires an artifactId");
        }
        repo = (repo == null || repo.isBlank()) ? artifactId : repo;
        kind = (kind == null) ? EdgeKind.DEPENDENCY : kind;
    }

    /**
     * Convenience constructor for identity-only edges — the
     * {@link CascadeAssembler}'s start-edge case and tests that do
     * not care about edge kind. Defaults {@code kind} to
     * {@link EdgeKind#DEPENDENCY}.
     *
     * @param groupId    the neighbour's reactor-root Maven
     *                   {@code groupId}
     * @param artifactId the neighbour's reactor-root Maven
     *                   {@code artifactId}
     * @param repo       the neighbour's on-disk directory / GitHub
     *                   repo name
     * @param url        the neighbour's canonical upstream git URL
     */
    public CascadeEdge(String groupId, String artifactId,
                       String repo, String url) {
        this(groupId, artifactId, repo, url, EdgeKind.DEPENDENCY);
    }

    /**
     * Returns the {@code groupId:artifactId} coordinate string.
     *
     * @return the GA coordinate, e.g.
     *         {@code network.ike.tooling:ike-tooling}
     */
    public String ga() {
        return groupId + ":" + artifactId;
    }

    /**
     * Returns the canonical IKE version property that pins this
     * neighbour: {@code groupId·artifactId} (U+00B7 MIDDLE DOT).
     * Derived — no field stores it — because under the {@code ${G·A}}
     * convention the property name is exactly the coordinate. See
     * IKE-Network/ike-issues#470, #496.
     *
     * @return the canonical {@code G·A} version-property name
     */
    public String versionProperty() {
        return groupId + "·" + artifactId;
    }
}
