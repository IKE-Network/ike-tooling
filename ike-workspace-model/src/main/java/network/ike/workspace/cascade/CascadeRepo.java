package network.ike.workspace.cascade;

import java.util.List;

/**
 * One node in the assembled IKE release cascade graph
 * (IKE-Network/ike-issues#402, #420).
 *
 * <p>A node pairs a project's identity — its reactor-root Maven
 * coordinates and repository locators — with the {@link ProjectCascade}
 * parsed from that project's own {@code src/main/cascade/release-cascade.yaml}.
 * Nodes are produced only by {@link CascadeAssembler}, which traverses
 * the per-project manifests and stitches them into a single ordered
 * {@link ReleaseCascade}.
 *
 * <p>Identity is keyed off the Maven {@code groupId} +
 * {@code artifactId}. The {@code repo} and {@code url} fields are pure
 * locators — the on-disk directory and the canonical git URL the
 * cascade executor uses to reach the project.
 *
 * @param groupId    the project's reactor-root Maven {@code groupId};
 *                   the primary identity key
 * @param artifactId the project's reactor-root Maven {@code artifactId}
 * @param repo       the on-disk directory / GitHub repo name
 * @param url        the canonical upstream git URL, or {@code null}
 *                   when unknown
 * @param cascade    the project's own parsed {@code release-cascade.yaml}
 */
public record CascadeRepo(String groupId, String artifactId,
                           String repo, String url,
                           ProjectCascade cascade) {

    /**
     * Canonical constructor — validates the identity coordinates and
     * the embedded {@link ProjectCascade}.
     */
    public CascadeRepo {
        if (groupId == null || groupId.isBlank()) {
            throw new IllegalArgumentException(
                    "cascade node requires a groupId");
        }
        if (artifactId == null || artifactId.isBlank()) {
            throw new IllegalArgumentException(
                    "cascade node requires an artifactId");
        }
        if (repo == null || repo.isBlank()) {
            repo = artifactId;
        }
        if (cascade == null) {
            throw new IllegalArgumentException(
                    "cascade node requires a ProjectCascade");
        }
    }

    /**
     * Returns the {@code groupId:artifactId} coordinate string.
     *
     * @return the GA coordinate, e.g. {@code network.ike.tooling:ike-tooling}
     */
    public String ga() {
        return groupId + ":" + artifactId;
    }

    /**
     * The upstream edges — the projects this one consumes.
     *
     * @return this project's {@code upstream} edges; never {@code null}
     */
    public List<CascadeEdge> upstream() {
        return cascade.upstream();
    }

    /**
     * The downstream edges — the projects that consume this one.
     *
     * @return this project's {@code downstream} edges; never {@code null}
     */
    public List<CascadeEdge> downstream() {
        return cascade.downstream();
    }

    /**
     * The groupIds this project consumes, drawn from its
     * {@code upstream} edges.
     *
     * @return the upstream groupIds; never {@code null}
     */
    public List<String> consumes() {
        return cascade.upstream().stream()
                .map(CascadeEdge::groupId).toList();
    }

    /**
     * Whether this project is the head of the cascade.
     *
     * @return {@code true} if it declares no upstream edge
     */
    public boolean head() {
        return cascade.head();
    }

    /**
     * Whether this project is the terminus of the cascade.
     *
     * @return {@code true} if it declares no downstream edge
     */
    public boolean terminal() {
        return cascade.terminal();
    }
}
