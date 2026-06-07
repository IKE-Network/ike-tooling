package network.ike.workspace;

import java.nio.file.Path;
import java.util.List;

/**
 * A dependency-ordered set of release members — the scope an artifact /
 * release-style workspace verb (release, align) acts on
 * (IKE-Network/ike-issues#610, under the console/engine boundary, #601).
 *
 * <p>Where a {@link WorkingSet} is co-located checkouts in declaration order
 * (for working-tree verbs), a cohort is <strong>topologically ordered</strong>
 * — a member never precedes an upstream it depends on — and <strong>excludes
 * the workspace aggregator root</strong>, which is not a released artifact.
 * A cohort may also be <em>decentralized</em>: the foundation release cascade
 * spans sibling repositories that share no common root, assembled from each
 * project's {@code release-cascade.yaml} (modelled by
 * {@link network.ike.workspace.cascade.ReleaseCascade}).
 *
 * <p>Resolved from context by {@link CohortResolver}: a single repository is
 * a cohort of one; a {@code workspace.yaml} is its subprojects in topological
 * order.
 *
 * @param members       the release members in topological (upstream-first)
 *                      order
 * @param decentralized {@code true} when the members are sibling repositories
 *                      with no common root (the foundation cascade);
 *                      {@code false} for a single repo or a co-located
 *                      workspace
 */
public record Cohort(List<Member> members, boolean decentralized) {

    /**
     * The number of release members in this cohort.
     *
     * @return the member count
     */
    public int size() {
        return members.size();
    }

    /**
     * Whether this cohort has no members.
     *
     * @return {@code true} if empty
     */
    public boolean isEmpty() {
        return members.isEmpty();
    }

    /**
     * One release member of a {@link Cohort}.
     *
     * @param name      the subproject / repository name
     * @param directory the member's directory
     */
    public record Member(String name, Path directory) {}
}
