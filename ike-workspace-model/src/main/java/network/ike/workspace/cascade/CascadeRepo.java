package network.ike.workspace.cascade;

import java.util.List;

/**
 * One repository entry in the IKE foundation release cascade.
 *
 * <p>Backs the declarative {@code release-cascade.yaml} manifest
 * introduced by IKE-Network/ike-issues#402. Entries appear in the
 * manifest in topological order: a repo is released only after every
 * groupId it {@linkplain #consumes() consumes} has released.
 *
 * <p>Cascade members are keyed off their Maven coordinates
 * ({@code groupId} + {@code artifactId}) — the same identity a
 * releasing project reports from its own reactor-root POM. The
 * {@code repo} field is a pure on-disk locator (a directory name),
 * distinct from identity, used by {@code ws:cascade-foundation-publish}
 * to find sibling checkouts.
 *
 * @param groupId    the reactor-root Maven {@code groupId} this repo
 *                   releases under (e.g. {@code network.ike.tooling});
 *                   the primary identity key
 * @param artifactId the reactor-root Maven {@code artifactId}
 *                   (e.g. {@code ike-tooling})
 * @param repo       the on-disk directory / GitHub repo name; defaults
 *                   to {@code artifactId} when omitted in the manifest
 * @param url        the canonical upstream git URL of this repo, or
 *                   {@code null} when the manifest omits it. The
 *                   reference origin — local checkouts and CI VCS
 *                   roots may legitimately use a different remote (a
 *                   fork, an SSH alias, an internal mirror)
 * @param consumes   groupIds of upstream cascade members whose
 *                   artifacts this repo depends on; never {@code null}
 *                   (an empty list means the repo is at the root of
 *                   the cascade)
 * @param terminal   {@code true} when this repo positively declares
 *                   itself the end of the cascade — it has no
 *                   downstream consumer and the cascade stops here.
 *                   Asserted in the manifest rather than inferred from
 *                   the absence of consumers, so a forgotten downstream
 *                   edge surfaces as a validation error
 *                   (IKE-Network/ike-issues#419)
 */
public record CascadeRepo(String groupId, String artifactId,
                           String repo, String url,
                           List<String> consumes, boolean terminal) {

    /**
     * Canonical constructor — validates the identity coordinates,
     * defaults {@code repo} to {@code artifactId}, and defensively
     * copies {@code consumes}.
     */
    public CascadeRepo {
        if (groupId == null || groupId.isBlank()) {
            throw new IllegalArgumentException(
                    "cascade entry requires a groupId");
        }
        if (artifactId == null || artifactId.isBlank()) {
            throw new IllegalArgumentException(
                    "cascade entry requires an artifactId");
        }
        repo = (repo == null || repo.isBlank()) ? artifactId : repo;
        consumes = consumes == null ? List.of() : List.copyOf(consumes);
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
