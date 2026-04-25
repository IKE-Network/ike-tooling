package network.ike.workspace;

import java.util.List;

/**
 * The full set of rules consulted by {@code versions-upgrade-draft}
 * to decide what to upgrade and what to leave alone.
 *
 * <p>Loaded from {@code versions-upgrade-rules.yaml} (typically at the
 * workspace root or module root). Held as a record so callers can
 * pass it around and tests can construct one in-memory.
 *
 * <p>Resolution semantics: for each candidate coordinate, walk
 * {@link #rules()} in order; the first rule whose patterns match wins.
 * If no rule matches, {@link #defaultAction()} is applied (typically
 * {@link VersionUpgradeRule.Action#BLOCK} so unknown coordinates are
 * never silently upgraded).
 *
 * <p>Example YAML:
 * <pre>{@code
 * schema-version: "1.0"
 * default-action: block
 * rules:
 *   - match: "network.ike.*:*"
 *     action: allow
 *   - match: "org.junit.jupiter:*"
 *     action: allow
 *   - match: "com.example:frozen-lib"
 *     action: pin
 *     version: "1.2.3"
 *   - match: "com.example:broken-lib"
 *     action: block
 *     reason: "Known to fail integration tests"
 * }</pre>
 *
 * @param schemaVersion ruleset schema version (currently "1.0")
 * @param defaultAction action to take when no rule matches; typically
 *                      {@link VersionUpgradeRule.Action#BLOCK}
 * @param rules         ordered list of rules; first match wins
 */
public record VersionUpgradeRules(
        String schemaVersion,
        VersionUpgradeRule.Action defaultAction,
        List<VersionUpgradeRule> rules
) {

    /**
     * Canonical constructor that defensively copies {@code rules} into
     * an immutable list.
     *
     * @param schemaVersion see {@link #schemaVersion()}
     * @param defaultAction see {@link #defaultAction()}
     * @param rules         see {@link #rules()}; null is normalized to
     *                      an empty list
     */
    public VersionUpgradeRules {
        rules = rules == null ? List.of() : List.copyOf(rules);
    }

    /**
     * Resolve the action for a candidate coordinate. Walks the rule
     * list in declaration order; the first matching rule wins. If no
     * rule matches, returns a synthetic rule carrying the default
     * action with patterns {@code "*":"*"} and reason
     * {@code "no rule matched (default-action)"}.
     *
     * @param groupId    candidate's groupId
     * @param artifactId candidate's artifactId
     * @return the matching rule, or a synthetic default-action rule
     */
    public VersionUpgradeRule resolve(String groupId, String artifactId) {
        for (VersionUpgradeRule rule : rules) {
            if (rule.matches(groupId, artifactId)) {
                return rule;
            }
        }
        return new VersionUpgradeRule("*", "*", defaultAction, null,
                "no rule matched (default-action)");
    }

    /**
     * Build a permissive default ruleset useful for tests and ad-hoc
     * use: every coordinate matches an {@link VersionUpgradeRule.Action#ALLOW}
     * rule, no blocklist, no pins.
     *
     * @return a ruleset that allows everything
     */
    public static VersionUpgradeRules allowAll() {
        return new VersionUpgradeRules("1.0",
                VersionUpgradeRule.Action.ALLOW, List.of());
    }
}
