package network.ike.workspace.cascade;

/**
 * A directed edge in the IKE release cascade — a pointer from one
 * project to one of its cascade neighbours.
 *
 * <p>An edge carries the neighbour's reactor-root Maven
 * {@link MavenCoordinate}, an on-disk {@code repo} name, a git
 * {@code url}, and an {@link EdgeKind} naming the POM site the edge
 * was derived from. The version property the alignment step
 * rewrites — once {@code ${G·A}} naming is universal — is mechanical:
 * {@link MavenCoordinate#versionProperty()} returns
 * {@code groupId·artifactId}, so no field stores it separately. The
 * pre-derivation per-project {@code release-cascade.yaml} manifests
 * carried a {@code version-property} value here; the derivation
 * pipeline introduced by IKE-Network/ike-issues#496 replaces it.
 *
 * @param coordinate the neighbour's reactor-root coordinate; the
 *                   primary identity key
 * @param repo       the neighbour's on-disk directory / GitHub repo
 *                   name; defaults to
 *                   {@link MavenCoordinate#artifactId()} when blank
 * @param url        the neighbour's canonical upstream git URL, or
 *                   {@code null} when unknown
 * @param kind       the Maven model site this edge was derived from;
 *                   defaults to {@link EdgeKind#DEPENDENCY} when
 *                   {@code null}
 */
public record CascadeEdge(MavenCoordinate coordinate,
                           String repo, String url,
                           EdgeKind kind) {

    /**
     * Canonical constructor — validates the coordinate, defaults
     * {@code repo} to the artifactId, and defaults {@code kind} to
     * {@link EdgeKind#DEPENDENCY}.
     */
    public CascadeEdge {
        if (coordinate == null) {
            throw new IllegalArgumentException(
                    "cascade edge requires a MavenCoordinate");
        }
        repo = (repo == null || repo.isBlank())
                ? coordinate.artifactId() : repo;
        kind = (kind == null) ? EdgeKind.DEPENDENCY : kind;
    }

    /**
     * Convenience constructor accepting raw {@code groupId} /
     * {@code artifactId} strings. Wraps them into a
     * {@link MavenCoordinate}. Defaults {@code kind} to
     * {@link EdgeKind#DEPENDENCY}.
     *
     * @param groupId    the Maven {@code groupId}
     * @param artifactId the Maven {@code artifactId}
     * @param repo       the on-disk directory / GitHub repo name
     * @param url        the canonical upstream git URL
     */
    public CascadeEdge(String groupId, String artifactId,
                       String repo, String url) {
        this(new MavenCoordinate(groupId, artifactId),
                repo, url, EdgeKind.DEPENDENCY);
    }

    /**
     * Convenience constructor accepting raw {@code groupId} /
     * {@code artifactId} strings plus an explicit kind.
     *
     * @param groupId    the Maven {@code groupId}
     * @param artifactId the Maven {@code artifactId}
     * @param repo       the on-disk directory / GitHub repo name
     * @param url        the canonical upstream git URL
     * @param kind       the edge kind
     */
    public CascadeEdge(String groupId, String artifactId,
                       String repo, String url, EdgeKind kind) {
        this(new MavenCoordinate(groupId, artifactId), repo, url, kind);
    }

    /**
     * Returns the coordinate's {@code groupId}. Delegates to
     * {@link MavenCoordinate#groupId()} for compatibility with the
     * pre-record API.
     *
     * @return the {@code groupId}
     */
    public String groupId() {
        return coordinate.groupId();
    }

    /**
     * Returns the coordinate's {@code artifactId}. Delegates to
     * {@link MavenCoordinate#artifactId()} for compatibility with
     * the pre-record API.
     *
     * @return the {@code artifactId}
     */
    public String artifactId() {
        return coordinate.artifactId();
    }

    /**
     * Returns the {@code "groupId:artifactId"} display form.
     *
     * @return the {@code G:A} string
     */
    public String ga() {
        return coordinate.ga();
    }

    /**
     * Returns the canonical IKE version-property name —
     * {@code groupId·artifactId} (U+00B7 MIDDLE DOT). Derived from
     * the coordinate; see {@link MavenCoordinate#versionProperty()}.
     *
     * @return the canonical {@code G·A} version-property name
     */
    public String versionProperty() {
        return coordinate.versionProperty();
    }
}
