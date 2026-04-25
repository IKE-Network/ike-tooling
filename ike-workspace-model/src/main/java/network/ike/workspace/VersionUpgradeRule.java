package network.ike.workspace;

/**
 * A single rule in a {@link VersionUpgradeRules} ruleset.
 *
 * <p>Each rule matches coordinates on a {@code groupId} and
 * {@code artifactId} pattern (glob-style: {@code *} matches any
 * sequence of characters within a single segment-of-id, no
 * cross-segment surprises). Patterns are matched literally with
 * {@code *} as a wildcard; there is no regex escaping for callers
 * to learn.
 *
 * <p>The {@link #action()} controls what {@code versions-upgrade-draft}
 * proposes for a matched coordinate:
 * <ul>
 *   <li>{@link Action#ALLOW} — propose the highest non-SNAPSHOT
 *       candidate returned by the resolver</li>
 *   <li>{@link Action#BLOCK} — never propose an upgrade; existing
 *       version stays. {@code reason} is surfaced in the plan as
 *       {@code BLOCKED}.</li>
 *   <li>{@link Action#PIN} — propose {@link #pinnedVersion()}
 *       regardless of what the resolver returns. Used to hold a
 *       coordinate at a known-good version.</li>
 * </ul>
 *
 * @param groupIdPattern    glob pattern matched against the candidate's
 *                          groupId; {@code "*"} matches everything
 * @param artifactIdPattern glob pattern matched against the candidate's
 *                          artifactId; {@code "*"} matches everything
 * @param action            what to do when this rule matches
 * @param pinnedVersion     version to pin to, when {@code action} is
 *                          {@link Action#PIN}; otherwise null
 * @param reason            optional human-readable explanation, surfaced
 *                          in the plan for non-{@link Action#ALLOW}
 *                          outcomes
 */
public record VersionUpgradeRule(
        String groupIdPattern,
        String artifactIdPattern,
        Action action,
        String pinnedVersion,
        String reason
) {

    /**
     * What a matching rule should do for a candidate coordinate.
     */
    public enum Action {
        /** Propose the highest non-SNAPSHOT candidate. */
        ALLOW,
        /** Never upgrade; keep the existing version. */
        BLOCK,
        /** Force the version to {@link VersionUpgradeRule#pinnedVersion()}. */
        PIN
    }

    /**
     * Test whether this rule matches a {@code groupId:artifactId}
     * coordinate.
     *
     * @param groupId    the candidate coordinate's groupId
     * @param artifactId the candidate coordinate's artifactId
     * @return true if both patterns match
     */
    public boolean matches(String groupId, String artifactId) {
        return globMatch(groupIdPattern, groupId)
                && globMatch(artifactIdPattern, artifactId);
    }

    /**
     * Glob match: {@code *} matches any sequence of any characters
     * (including dots), every other character matches literally. Case
     * sensitive. Used for both groupId and artifactId patterns.
     *
     * <p>Implementation is a hand-rolled two-pointer scan rather than
     * regex translation — the patterns we accept are tiny and we want
     * predictable semantics, not full regex.
     *
     * @param pattern   the glob pattern (e.g. {@code "network.ike.*"})
     * @param candidate the string to test
     * @return true if {@code candidate} matches {@code pattern}
     */
    public static boolean globMatch(String pattern, String candidate) {
        if (pattern == null || candidate == null) {
            return false;
        }
        int p = 0;
        int c = 0;
        int starP = -1;
        int starC = 0;
        while (c < candidate.length()) {
            if (p < pattern.length() && pattern.charAt(p) == '*') {
                starP = p;
                starC = c;
                p++;
            } else if (p < pattern.length()
                    && pattern.charAt(p) == candidate.charAt(c)) {
                p++;
                c++;
            } else if (starP != -1) {
                p = starP + 1;
                starC++;
                c = starC;
            } else {
                return false;
            }
        }
        while (p < pattern.length() && pattern.charAt(p) == '*') {
            p++;
        }
        return p == pattern.length();
    }
}
