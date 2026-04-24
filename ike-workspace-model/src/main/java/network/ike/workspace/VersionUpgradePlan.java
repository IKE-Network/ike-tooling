package network.ike.workspace;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The top-level versions-upgrade plan, deserialized from
 * {@code versions-upgrade-plan.yaml}.
 *
 * <p>Produced by {@code versions-upgrade-draft} and consumed by
 * {@code versions-upgrade-publish}. Human-editable between the two
 * phases: the user can remove lines they don't want applied, pin a
 * specific target version, or delete an entire node section to skip
 * that node.
 *
 * <p>The {@link #planHash()} is computed over the logical content
 * (nodes only, excluding timestamps and fingerprints) by
 * {@code VersionUpgradePlanWriter} at write time. {@code versions-upgrade-draft}
 * uses the hash to detect whether the user has edited the plan
 * before re-drafting; {@code versions-upgrade-publish} stamps it
 * into the commit message for auditability.
 *
 * <p>The {@link #pomFingerprint()} is computed over the set of POM
 * files the draft was generated against. If any POM changes between
 * {@code draft} and {@code publish}, publish aborts and asks the
 * user to regenerate the plan — staleness is never silently resolved.
 *
 * @param schemaVersion    plan schema version (currently "1.0")
 * @param generated        ISO-8601 timestamp when the plan was drafted
 * @param scope            whether the plan covers a single module or
 *                         a full workspace; see {@link VersionUpgradeScope}
 * @param planHash         SHA-256 of the plan's logical content,
 *                         used for edit detection and commit audit;
 *                         may be null for in-memory plans not yet
 *                         serialized
 * @param pomFingerprint   SHA-256 of the POM files the plan was
 *                         computed from, used to detect stale plans
 *                         at publish time; may be null for in-memory
 *                         plans not yet serialized
 * @param ikeToolingVersion the {@code ike-tooling.version} property
 *                         value at draft time, surfaced in the plan
 *                         header for human review
 * @param nodes            per-node upgrade entries, insertion-ordered
 *                         (topological order for workspace plans,
 *                         single entry for module plans)
 */
public record VersionUpgradePlan(
        String schemaVersion,
        String generated,
        VersionUpgradeScope scope,
        String planHash,
        String pomFingerprint,
        String ikeToolingVersion,
        Map<String, NodeVersionUpgrade> nodes
) {
    /**
     * Canonical constructor that normalizes {@code nodes} to an
     * insertion-ordered, immutable map.
     *
     * @param schemaVersion     see {@link #schemaVersion()}
     * @param generated         see {@link #generated()}
     * @param scope             see {@link #scope()}
     * @param planHash          see {@link #planHash()}
     * @param pomFingerprint    see {@link #pomFingerprint()}
     * @param ikeToolingVersion see {@link #ikeToolingVersion()}
     * @param nodes             see {@link #nodes()}; null is normalized
     *                          to an empty map
     */
    public VersionUpgradePlan {
        if (nodes == null || nodes.isEmpty()) {
            nodes = Map.of();
        } else {
            nodes = Collections.unmodifiableMap(new LinkedHashMap<>(nodes));
        }
    }
}
