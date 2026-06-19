package network.ike.plugin;

/**
 * A single upstream-version upgrade applied by the release-prep
 * "align upstream cascade versions" step (ReleasePrep B8,
 * IKE-Network/ike-issues#419): a {@code <G>__GA__<A>__VERSION} pin (or a
 * {@code <parent>} version) rewritten from {@code current} to
 * {@code latest} so a single-repo release never ships on a stale
 * foundation.
 *
 * <p>Captured as structured data — rather than a pre-formatted log
 * string — so the same upgrade facts can be rendered into the align
 * commit message, the GitHub Release body, and the run report without
 * re-parsing (IKE-Network/ike-issues#706). A cascade-only release (a
 * foundation rebuild that consumed a newer upstream but had no changes
 * of its own) carries these as its only "what changed," so they must
 * survive from prep all the way to the release notes.
 *
 * @param groupId   the upgraded upstream's groupId (e.g. {@code network.ike.tooling})
 * @param artifactId the upgraded upstream's artifactId (e.g. {@code ike-tooling})
 * @param current   the version the pin held before alignment (e.g. {@code 221})
 * @param latest    the released version the pin was raised to (e.g. {@code 222})
 */
public record CascadeBump(String groupId, String artifactId,
                          String current, String latest) {

    /**
     * The {@code groupId:artifactId} coordinate label.
     *
     * @return the GA coordinate (e.g. {@code network.ike.tooling:ike-tooling})
     */
    public String ga() {
        return groupId + ":" + artifactId;
    }

    /**
     * A compact one-line upgrade rendering for commit messages — the
     * short artifact name and the version transition, e.g.
     * {@code ike-tooling 221→222}.
     *
     * @return the compact {@code <artifactId> <current>→<latest>} form
     */
    public String compact() {
        return artifactId + " " + current + "→" + latest;
    }
}
