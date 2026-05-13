package network.ike.workspace;

/**
 * Predicates that classify "no-op" version-upgrade entries — the ones
 * where {@code from == to} so no actual upgrade is being proposed.
 *
 * <p>The resolver emits an entry for every property reference, literal
 * version, and parent declaration it finds in a POM, regardless of
 * whether a newer version is available. That gives an audit trail
 * (the model carries every coordinate it scanned), but it also means
 * the writer would otherwise emit dozens of {@code "from: 13.0.0 / to:
 * 13.0.0"} stanzas per node — pure noise to the user.
 *
 * <p>These predicates split the {@code from == to} cases into:
 *
 * <ul>
 *   <li>{@link #isPureNoise(VersionUpgradeStatus, String, String, String)} —
 *       blocked with the default-action reason. The ruleset has no
 *       opinion and there's nothing to upgrade. Drop silently.</li>
 *   <li>{@link #isInformationalSameVersion(VersionUpgradeStatus, String, String, String)} —
 *       blocked for any other reason (conflict, ambiguity). The user
 *       should see this as a Warning — there's a real problem to act
 *       on, even though no upgrade was proposed.</li>
 * </ul>
 *
 * <p>{@code ws:versions-upgrade-draft} (and the single-module
 * {@code ike:} variant) use these to filter the plan-writer output
 * and to drive the Warnings section of the human-readable report.
 *
 * <p>ike-issues#384.
 */
public final class VersionUpgradeNoise {

    /** The default-action reason emitted by {@link VersionUpgradeRules}. */
    public static final String DEFAULT_ACTION_REASON =
            "no rule matched (default-action)";

    private VersionUpgradeNoise() {}

    /**
     * Return {@code true} when the entry is pure noise — a {@code
     * from == to} no-op blocked by the ruleset's default action. Plan
     * writers should skip these entirely; report builders should not
     * mention them.
     *
     * <p>A {@code from == to} entry with status {@code READY} is also
     * treated as pure noise: there's nothing to apply, so the entry
     * is meaningless.
     *
     * @param status      the entry's upgrade status
     * @param fromVersion current version
     * @param toVersion   proposed target version
     * @param reason      the entry's reason string (nullable)
     * @return {@code true} if this entry should be silently dropped
     */
    public static boolean isPureNoise(VersionUpgradeStatus status,
                                       String fromVersion,
                                       String toVersion,
                                       String reason) {
        if (!isSameVersion(fromVersion, toVersion)) return false;
        if (status == VersionUpgradeStatus.READY) return true;
        if (status == VersionUpgradeStatus.BLOCKED) {
            return DEFAULT_ACTION_REASON.equals(reason);
        }
        return false;
    }

    /**
     * Return {@code true} when the entry is a {@code from == to}
     * blocked entry whose reason is meaningful (e.g., a consumer
     * conflict). These deserve to surface as a Warning even though
     * no upgrade is proposed.
     *
     * @param status      the entry's upgrade status
     * @param fromVersion current version
     * @param toVersion   proposed target version
     * @param reason      the entry's reason string (nullable)
     * @return {@code true} if this entry should appear in the
     *         Warnings section
     */
    public static boolean isInformationalSameVersion(
            VersionUpgradeStatus status,
            String fromVersion,
            String toVersion,
            String reason) {
        if (!isSameVersion(fromVersion, toVersion)) return false;
        if (status != VersionUpgradeStatus.BLOCKED) return false;
        return reason != null && !DEFAULT_ACTION_REASON.equals(reason);
    }

    // ── Per-entry convenience wrappers ──────────────────────────────

    /** @see #isPureNoise(VersionUpgradeStatus, String, String, String) */
    public static boolean isPureNoise(ParentVersionUpgrade p) {
        return isPureNoise(p.status(), p.fromVersion(),
                p.toVersion(), p.reason());
    }

    /** @see #isPureNoise(VersionUpgradeStatus, String, String, String) */
    public static boolean isPureNoise(PropertyVersionUpgrade p) {
        return isPureNoise(p.status(), p.fromVersion(),
                p.toVersion(), p.reason());
    }

    /** @see #isPureNoise(VersionUpgradeStatus, String, String, String) */
    public static boolean isPureNoise(LiteralVersionUpgrade l) {
        return isPureNoise(l.status(), l.fromVersion(),
                l.toVersion(), l.reason());
    }

    /** @see #isInformationalSameVersion(VersionUpgradeStatus, String, String, String) */
    public static boolean isInformationalSameVersion(
            ParentVersionUpgrade p) {
        return isInformationalSameVersion(p.status(), p.fromVersion(),
                p.toVersion(), p.reason());
    }

    /** @see #isInformationalSameVersion(VersionUpgradeStatus, String, String, String) */
    public static boolean isInformationalSameVersion(
            PropertyVersionUpgrade p) {
        return isInformationalSameVersion(p.status(), p.fromVersion(),
                p.toVersion(), p.reason());
    }

    /** @see #isInformationalSameVersion(VersionUpgradeStatus, String, String, String) */
    public static boolean isInformationalSameVersion(
            LiteralVersionUpgrade l) {
        return isInformationalSameVersion(l.status(), l.fromVersion(),
                l.toVersion(), l.reason());
    }

    private static boolean isSameVersion(String from, String to) {
        if (from == null && to == null) return true;
        if (from == null || to == null) return false;
        return from.equals(to);
    }
}
