package network.ike.workspace.cascade;

/**
 * A directed edge in the IKE release cascade — a pointer from the
 * project that owns a {@code release-cascade.yaml} to one of its
 * cascade neighbours (IKE-Network/ike-issues#420).
 *
 * <p>The cascade is a loosely-coupled distributed system: every
 * project version-controls its own {@code src/main/cascade/release-cascade.yaml},
 * declaring only its own {@code upstream} and {@code downstream}
 * edges. The full ordered graph is assembled by traversing these
 * edges, never authored centrally.
 *
 * <p>An edge carries everything the assembler and the cascade
 * executor need to locate the neighbour: its Maven coordinates, an
 * on-disk {@code repo} directory name, and a git {@code url}. For an
 * {@code upstream} edge it also carries {@code versionProperty} — the
 * {@code ${X.version}} property the alignment step bumps to the
 * upstream's latest release. A {@code downstream} edge leaves
 * {@code versionProperty} {@code null}.
 *
 * @param groupId         the neighbour's reactor-root Maven
 *                        {@code groupId}; the primary identity key
 * @param artifactId      the neighbour's reactor-root Maven
 *                        {@code artifactId}
 * @param repo            the neighbour's on-disk directory / GitHub
 *                        repo name; defaults to {@code artifactId}
 *                        when omitted in the manifest
 * @param url             the neighbour's canonical upstream git URL,
 *                        or {@code null} when the manifest omits it
 * @param versionProperty the {@code ${X.version}} POM property that
 *                        pins the neighbour, for an {@code upstream}
 *                        edge; {@code null} on a {@code downstream}
 *                        edge
 */
public record CascadeEdge(String groupId, String artifactId,
                           String repo, String url,
                           String versionProperty) {

    /**
     * Canonical constructor — validates the identity coordinates and
     * defaults {@code repo} to {@code artifactId}.
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
    }

    /**
     * Returns the {@code groupId:artifactId} coordinate string.
     *
     * @return the GA coordinate, e.g. {@code network.ike.tooling:ike-tooling}
     */
    public String ga() {
        return groupId + ":" + artifactId;
    }
}
