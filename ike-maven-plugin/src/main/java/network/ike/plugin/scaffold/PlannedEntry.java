package network.ike.plugin.scaffold;

import java.util.List;
import java.util.Objects;

/**
 * One entry in a {@link ScaffoldPlan}: the manifest entry, the
 * {@link TierAction} that publish should take, and — for
 * {@link ScaffoldTier#MODEL_MANAGED} entries — the per-element
 * provenance to record in the lockfile.
 *
 * <p>File-based tiers use an empty {@code managedElements} list;
 * model-managed entries populate it.
 *
 * @param manifest         the manifest entry
 * @param action           the planned action
 * @param managedElements  per-element provenance for model-managed
 *                         entries; never {@code null}. Stored list is
 *                         unmodifiable.
 */
public record PlannedEntry(
        ManifestEntry manifest,
        TierAction action,
        List<ManagedElement> managedElements) {

    /** Canonical constructor with validation and defensive copying. */
    public PlannedEntry {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(action, "action");
        managedElements = managedElements == null
                ? List.of()
                : List.copyOf(managedElements);
        if (manifest.tier() != ScaffoldTier.MODEL_MANAGED
                && !managedElements.isEmpty()) {
            throw new IllegalArgumentException(
                    "managedElements only valid for model-managed; "
                            + "got " + manifest.tier().manifestValue());
        }
    }
}
