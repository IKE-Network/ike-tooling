package network.ike.plugin.scaffold;

import java.util.List;
import java.util.Objects;

/**
 * Plan-time decision from a {@link ModelAdapter}.
 *
 * <p>Model-managed entries differ from file-based tiers: the lockfile
 * records per-element provenance in {@link ManagedElement} rather than
 * a whole-file checksum. The adapter therefore returns both the
 * {@link TierAction} (for the scaffold applier's file I/O) and the
 * element list (for the lockfile update).
 *
 * <p>For {@link TierAction.UpToDate} and {@link TierAction.Skip} the
 * element list describes what is (or would be) present; for
 * {@link TierAction.Write} it describes what will be in place
 * <em>after</em> the write succeeds.
 *
 * @param action           the action the applier should take
 * @param managedElements  per-element provenance for the lockfile
 *                         entry after {@code action} is applied; never
 *                         {@code null}. The stored list is
 *                         unmodifiable.
 */
public record ModelPlanResult(
        TierAction action,
        List<ManagedElement> managedElements) {

    /** Canonical constructor with defensive copying. */
    public ModelPlanResult {
        Objects.requireNonNull(action, "action");
        managedElements = managedElements == null
                ? List.of()
                : List.copyOf(managedElements);
    }
}
