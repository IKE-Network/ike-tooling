package network.ike.workspace.cascade;

import java.util.List;

/**
 * One node in the assembled IKE release cascade graph
 * (IKE-Network/ike-issues#402, #420, #496).
 *
 * <p>A node pairs a project's identity — its reactor-root Maven
 * {@link MavenCoordinate} and repository locators — with the
 * {@link ProjectCascade} parsed from that project's own
 * {@code src/main/cascade/release-cascade.yaml}. Nodes are produced
 * only by {@link CascadeAssembler}, which traverses the per-project
 * manifests and stitches them into a single ordered
 * {@link ReleaseCascade}.
 *
 * <p>Two identities live on the node. The {@link #coordinate} names
 * <em>the coordinate the assembler started from</em> for this node —
 * the entry-point used to find the node's {@link ProjectCascade}.
 * The {@link #repositoryKey} names <em>the repository</em> the
 * coordinate belongs to (the {@code <scm>}-derived join key), and is
 * the durable node identity once #496 part D collapses self-edges
 * and coordinate aliases onto the repository. {@code repositoryKey}
 * may be {@code null} on nodes assembled without a
 * {@link RepositoryKeyResolver} — older assemblies and tests are
 * unaffected.
 *
 * <p>The {@code repo} and {@code url} fields are pure locators —
 * the on-disk directory name and the canonical upstream git URL the
 * cascade executor uses to reach the project.
 *
 * @param coordinate    the project's reactor-root coordinate
 * @param repo          the on-disk directory / GitHub repo name
 * @param url           the canonical upstream git URL, or
 *                      {@code null} when unknown
 * @param repositoryKey the {@code <scm>}-derived repository
 *                      identity; {@code null} when the assembler had
 *                      no {@link RepositoryKeyResolver}
 * @param cascade       the project's own parsed
 *                      {@code release-cascade.yaml}
 */
public record CascadeRepo(MavenCoordinate coordinate,
                           String repo, String url,
                           RepositoryKey repositoryKey,
                           ProjectCascade cascade) {

    /**
     * Canonical constructor — validates the coordinate and the
     * embedded {@link ProjectCascade}, and defaults {@code repo}
     * to the artifactId when blank.
     */
    public CascadeRepo {
        if (coordinate == null) {
            throw new IllegalArgumentException(
                    "cascade node requires a MavenCoordinate");
        }
        if (repo == null || repo.isBlank()) {
            repo = coordinate.artifactId();
        }
        if (cascade == null) {
            throw new IllegalArgumentException(
                    "cascade node requires a ProjectCascade");
        }
    }

    /**
     * Convenience constructor for callers that have no
     * {@link RepositoryKey} yet — older assemblies and tests. The
     * {@code repositoryKey} field is set to {@code null}.
     *
     * @param coordinate the project's coordinate
     * @param repo       the on-disk directory / GitHub repo name
     * @param url        the canonical upstream git URL, or
     *                   {@code null}
     * @param cascade    the project's parsed manifest
     */
    public CascadeRepo(MavenCoordinate coordinate,
                       String repo, String url,
                       ProjectCascade cascade) {
        this(coordinate, repo, url, null, cascade);
    }

    /**
     * Convenience constructor accepting raw {@code groupId} /
     * {@code artifactId} strings. Wraps them into a
     * {@link MavenCoordinate}.
     *
     * @param groupId    the Maven {@code groupId}
     * @param artifactId the Maven {@code artifactId}
     * @param repo       the on-disk directory / GitHub repo name
     * @param url        the canonical upstream git URL
     * @param cascade    the project's parsed manifest
     */
    public CascadeRepo(String groupId, String artifactId,
                       String repo, String url,
                       ProjectCascade cascade) {
        this(new MavenCoordinate(groupId, artifactId),
                repo, url, null, cascade);
    }

    /**
     * Convenience constructor accepting raw {@code groupId} /
     * {@code artifactId} strings plus a {@link RepositoryKey}.
     *
     * @param groupId       the Maven {@code groupId}
     * @param artifactId    the Maven {@code artifactId}
     * @param repo          the on-disk directory / GitHub repo name
     * @param url           the canonical upstream git URL
     * @param repositoryKey the repository identity, or {@code null}
     * @param cascade       the project's parsed manifest
     */
    public CascadeRepo(String groupId, String artifactId,
                       String repo, String url,
                       RepositoryKey repositoryKey,
                       ProjectCascade cascade) {
        this(new MavenCoordinate(groupId, artifactId),
                repo, url, repositoryKey, cascade);
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
     * The upstream edges — the projects this one consumes.
     *
     * @return this project's {@code upstream} edges; never
     *         {@code null}
     */
    public List<CascadeEdge> upstream() {
        return cascade.upstream();
    }

    /**
     * The downstream edges — the projects that consume this one.
     *
     * @return this project's {@code downstream} edges; never
     *         {@code null}
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
