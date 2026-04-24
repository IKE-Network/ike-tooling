package network.ike.plugin.scaffold;

import java.util.EnumMap;
import java.util.Map;

/**
 * Registry of file-based {@link TierHandler} instances keyed by
 * {@link ScaffoldTier}.
 *
 * <p>Contains one handler per file-based tier
 * ({@link ScaffoldTier#TOOL_OWNED}, {@link ScaffoldTier#TRACKED},
 * {@link ScaffoldTier#TRACKED_BLOCK}). {@link ScaffoldTier#MODEL_MANAGED}
 * entries are planned by model adapters rather than tier handlers and
 * are not present here — asking this registry for a model-managed
 * handler returns {@code null}.
 */
public final class TierHandlers {

    private final Map<ScaffoldTier, TierHandler> byTier;

    /** Create a registry with the default set of handlers. */
    public TierHandlers() {
        this(new ToolOwnedTierHandler(),
                new TrackedTierHandler(),
                new TrackedBlockTierHandler());
    }

    /**
     * Create a registry from an explicit set of handlers. Intended
     * for tests that want to swap in a stub.
     *
     * @param handlers one handler per file-based tier; duplicate tiers
     *                 cause an {@link IllegalArgumentException}
     */
    public TierHandlers(TierHandler... handlers) {
        Map<ScaffoldTier, TierHandler> map =
                new EnumMap<>(ScaffoldTier.class);
        for (TierHandler h : handlers) {
            if (map.putIfAbsent(h.tier(), h) != null) {
                throw new IllegalArgumentException(
                        "duplicate handler for tier " + h.tier());
            }
        }
        this.byTier = map;
    }

    /**
     * Look up the handler for a tier.
     *
     * @param tier the tier
     * @return the handler, or {@code null} if no handler is registered
     *         (e.g. {@link ScaffoldTier#MODEL_MANAGED})
     */
    public TierHandler get(ScaffoldTier tier) {
        return byTier.get(tier);
    }

    /**
     * Look up the handler for a tier, throwing if none is registered.
     *
     * @param tier the tier
     * @return the handler; never {@code null}
     * @throws ScaffoldException if no handler is registered for
     *                           {@code tier}
     */
    public TierHandler require(ScaffoldTier tier) {
        TierHandler h = byTier.get(tier);
        if (h == null) {
            throw new ScaffoldException(
                    "No TierHandler registered for tier "
                            + tier.manifestValue()
                            + " (model-managed entries go through "
                            + "ModelAdapter)");
        }
        return h;
    }
}
