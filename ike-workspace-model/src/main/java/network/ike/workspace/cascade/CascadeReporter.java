package network.ike.workspace.cascade;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Formats the cascade-awareness messages that {@code ike:release-draft}
 * and {@code ike:release-publish} print (IKE-Network/ike-issues#402).
 *
 * <p>This reporter does <em>not</em> inspect git history. After a
 * foundation repo is released, every downstream repo is definitionally
 * stale — it now carries an unreleased upstream dependency bump — so
 * the report simply enumerates {@link ReleaseCascade#downstreamOf}.
 * The point is visibility: the operator cannot finish a single-repo
 * release without seeing what the cascade says comes next.
 */
public final class CascadeReporter {

    private static final String RULE =
            "────────────────────────────────────────────────────────";

    private CascadeReporter() {}

    /**
     * Identifies which cascade entry the releasing project is, from
     * its own reactor-root POM coordinates.
     *
     * <p>An exact {@code groupId} + {@code artifactId} match is
     * preferred; a {@code groupId}-only match is the fallback (every
     * foundation repo has a distinct groupId, so this still resolves
     * uniquely).
     *
     * @param cascade    the parsed manifest
     * @param groupId    the releasing project's {@code groupId}
     * @param artifactId the releasing project's {@code artifactId}
     * @return the matching cascade entry, or empty if the project is
     *         not a foundation repo (an ordinary consumer)
     */
    public static Optional<CascadeRepo> self(ReleaseCascade cascade,
                                             String groupId,
                                             String artifactId) {
        if (groupId == null) {
            return Optional.empty();
        }
        if (artifactId != null) {
            Optional<CascadeRepo> exact =
                    cascade.findByCoordinates(groupId, artifactId);
            if (exact.isPresent()) {
                return exact;
            }
        }
        return cascade.find(groupId);
    }

    /**
     * Builds the post-release cascade footer for
     * {@code ike:release-publish}.
     *
     * @param cascade the parsed manifest
     * @param self    the cascade entry that was just released
     * @return log lines naming the now-stale downstream repos and the
     *         exact commands to continue the cascade
     */
    public static List<String> publishFooter(ReleaseCascade cascade,
                                             CascadeRepo self) {
        List<CascadeRepo> downstream = cascade.downstreamOf(self.groupId());
        List<String> lines = new ArrayList<>();
        lines.add(RULE);
        lines.add(" Foundation release cascade (release-cascade.yaml)");
        lines.add(RULE);
        lines.add("  ✓ " + self.repo() + " released.");
        if (downstream.isEmpty()) {
            lines.add("  End of the foundation cascade — no"
                    + " downstream foundation repos.");
            lines.add(RULE);
            return lines;
        }
        lines.add("  Downstream repos now carry an unreleased upstream"
                + " bump:");
        for (CascadeRepo r : downstream) {
            lines.add("      " + pad(r.repo()) + "consumes "
                    + String.join(", ", r.consumes()));
        }
        CascadeRepo next = downstream.get(0);
        lines.add("  Continue the cascade with the next repo:");
        lines.add("      cd ../" + next.repo()
                + " && mvn ike:release-publish");
        lines.add("  Or release the whole foundation in order:");
        lines.add("      mvn ws:cascade-foundation-publish");
        lines.add(RULE);
        return lines;
    }

    /**
     * Builds the cascade preview section for {@code ike:release-draft}.
     *
     * @param cascade the parsed manifest
     * @param self    the cascade entry being drafted for release
     * @return log lines naming the downstream repos this release will
     *         make stale; empty advisory when nothing is downstream
     */
    public static List<String> draftPreview(ReleaseCascade cascade,
                                            CascadeRepo self) {
        List<CascadeRepo> downstream = cascade.downstreamOf(self.groupId());
        List<String> lines = new ArrayList<>();
        lines.add(RULE);
        lines.add(" Foundation release cascade (release-cascade.yaml)");
        lines.add(RULE);
        if (downstream.isEmpty()) {
            lines.add("  " + self.repo() + " is last in the foundation"
                    + " cascade — nothing downstream to release.");
            lines.add(RULE);
            return lines;
        }
        lines.add("  Publishing " + self.repo() + " will make these"
                + " downstream repos stale:");
        for (CascadeRepo r : downstream) {
            lines.add("      " + r.repo());
        }
        lines.add("  After publishing, continue with"
                + " ws:cascade-foundation-publish.");
        lines.add(RULE);
        return lines;
    }

    private static String pad(String repo) {
        StringBuilder sb = new StringBuilder(repo);
        while (sb.length() < 15) {
            sb.append(' ');
        }
        return sb.toString();
    }
}
