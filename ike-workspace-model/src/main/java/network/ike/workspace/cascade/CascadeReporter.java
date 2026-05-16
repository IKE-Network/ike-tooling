package network.ike.workspace.cascade;

import java.util.ArrayList;
import java.util.List;

/**
 * Formats the cascade-awareness messages that {@code ike:release-draft}
 * and {@code ike:release-publish} print (IKE-Network/ike-issues#402,
 * #420).
 *
 * <p>In the decentralized cascade model every project version-controls
 * its own {@code src/main/cascade/release-cascade.yaml}, so the
 * releasing repo's own {@link ProjectCascade} is all the reporter
 * needs — no graph assembly, no sibling checkouts. The {@code downstream}
 * edges of that file are exactly the foundation repos a release makes
 * stale.
 *
 * <p>This reporter does not inspect git history: after a foundation
 * repo is released, every downstream repo is definitionally stale.
 * The point is visibility — the operator cannot finish a single-repo
 * release without seeing what the cascade says comes next.
 */
public final class CascadeReporter {

    private static final String RULE =
            "────────────────────────────────────────────────────────";

    private CascadeReporter() {}

    /**
     * Builds the post-release cascade footer for
     * {@code ike:release-publish}.
     *
     * @param local the releasing project's own parsed manifest
     * @param repo  the releasing project's repo/directory name
     * @return log lines naming the now-stale downstream repos and the
     *         exact commands to continue the cascade
     */
    public static List<String> publishFooter(ProjectCascade local,
                                             String repo) {
        List<String> lines = new ArrayList<>();
        lines.add(RULE);
        lines.add(" Foundation release cascade (release-cascade.yaml)");
        lines.add(RULE);
        lines.add("  ✓ " + repo + " released.");
        if (local.terminal()) {
            lines.add("  End of the foundation cascade — no"
                    + " downstream foundation repos.");
            lines.add(RULE);
            return lines;
        }
        lines.add("  Downstream repos now carry an unreleased upstream"
                + " bump:");
        for (CascadeEdge edge : local.downstream()) {
            lines.add("      " + edge.repo());
        }
        CascadeEdge next = local.downstream().get(0);
        lines.add("  Continue the cascade with the next repo:");
        lines.add("      cd ../" + next.repo()
                + " && mvn ike:release-publish");
        lines.add("  Or release the whole foundation in order:");
        lines.add("      mvn ike:release-cascade");
        lines.add(RULE);
        return lines;
    }

    /**
     * Builds the cascade preview section for {@code ike:release-draft}.
     *
     * @param local the project's own parsed manifest
     * @param repo  the project's repo/directory name
     * @return log lines naming the downstream repos this release will
     *         make stale; an end-of-cascade advisory when nothing is
     *         downstream
     */
    public static List<String> draftPreview(ProjectCascade local,
                                            String repo) {
        List<String> lines = new ArrayList<>();
        lines.add(RULE);
        lines.add(" Foundation release cascade (release-cascade.yaml)");
        lines.add(RULE);
        if (local.terminal()) {
            lines.add("  " + repo + " is last in the foundation"
                    + " cascade — nothing downstream to release.");
            lines.add(RULE);
            return lines;
        }
        lines.add("  Publishing " + repo + " will make these"
                + " downstream repos stale:");
        for (CascadeEdge edge : local.downstream()) {
            lines.add("      " + edge.repo());
        }
        lines.add("  After publishing, continue with"
                + " ike:release-cascade.");
        lines.add(RULE);
        return lines;
    }
}
