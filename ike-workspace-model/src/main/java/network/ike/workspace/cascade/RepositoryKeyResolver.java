package network.ike.workspace.cascade;

import java.util.Optional;

/**
 * Maps a Maven coordinate to the {@link RepositoryKey} of the
 * repository that produces it (IKE-Network/ike-issues#496 part C).
 *
 * <p>A coordinate alone cannot key the cascade graph: a single
 * reactor (one repository, one {@code <scm>}) publishes many
 * coordinates, and the cascade releases the repository as one unit.
 * To collapse coordinates onto their producing repositories the
 * assembler runs each coordinate through a resolver and groups nodes
 * by the returned key.
 *
 * <p>Implementations vary by execution context. A workstation goal
 * looking at a workspace of sibling checkouts uses
 * {@link SiblingRepositoryKeyResolver}; a CI agent with one repo
 * checked out resolves through Maven artifact resolution; a
 * cross-workspace cascade may consult the project registry
 * (IKE-Network/ike-issues#497).
 */
@FunctionalInterface
public interface RepositoryKeyResolver {

    /**
     * Returns the {@link RepositoryKey} of the repository that
     * produces a coordinate, or empty if the coordinate cannot be
     * located.
     *
     * @param groupId    the upstream's {@code groupId}
     * @param artifactId the upstream's {@code artifactId}
     * @return the producing repository's key, or empty if unknown
     */
    Optional<RepositoryKey> resolve(String groupId, String artifactId);
}
