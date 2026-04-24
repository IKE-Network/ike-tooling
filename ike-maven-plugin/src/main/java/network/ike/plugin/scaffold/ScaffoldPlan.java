package network.ike.plugin.scaffold;

import java.util.List;
import java.util.Objects;

/**
 * The output of {@link ScaffoldPlanner}: a list of
 * {@link PlannedEntry} records, one per manifest entry the planner
 * considered.
 *
 * <p>Plans are pure data — nothing on this record touches disk.
 * {@link ScaffoldApplier} is responsible for carrying a plan out.
 *
 * @param manifestStandardsVersion  the {@code standards-version} of
 *                                  the manifest the plan was built
 *                                  from
 * @param entries                   planned entries; never
 *                                  {@code null}. Stored list is
 *                                  unmodifiable.
 */
public record ScaffoldPlan(
        String manifestStandardsVersion,
        List<PlannedEntry> entries) {

    /** Canonical constructor with validation and defensive copying. */
    public ScaffoldPlan {
        Objects.requireNonNull(
                manifestStandardsVersion, "manifestStandardsVersion");
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    /**
     * Whether the plan has any {@link TierAction.Write} actions.
     *
     * @return {@code true} if at least one planned entry carries a
     *         {@link TierAction.Write}
     */
    public boolean hasWrites() {
        return entries.stream()
                .anyMatch(e -> e.action() instanceof TierAction.Write);
    }

    /**
     * Whether the plan has any {@link TierAction.Skip} actions.
     *
     * @return {@code true} if at least one planned entry carries a
     *         {@link TierAction.Skip}
     */
    public boolean hasSkips() {
        return entries.stream()
                .anyMatch(e -> e.action() instanceof TierAction.Skip);
    }
}
