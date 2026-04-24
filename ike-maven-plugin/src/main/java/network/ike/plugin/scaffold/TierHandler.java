package network.ike.plugin.scaffold;

import java.nio.file.Path;

/**
 * A tier-specific planner that converts a manifest entry + current
 * disk state + template bytes + prior lockfile state into a single
 * {@link TierAction}.
 *
 * <p>One implementation per file-based {@link ScaffoldTier}
 * ({@link ScaffoldTier#TOOL_OWNED}, {@link ScaffoldTier#TRACKED},
 * {@link ScaffoldTier#TRACKED_BLOCK}). Model-managed entries have their
 * own adapter machinery and do not go through {@code TierHandler}.
 *
 * <p>Handlers are pure — they must not touch disk, and they must be
 * safe to call in any order. The caller (scaffold planner) is
 * responsible for locating the file, reading its bytes, and passing
 * them in.
 */
public interface TierHandler {

    /**
     * Which tier this handler is responsible for.
     *
     * @return the tier; never {@code null}
     */
    ScaffoldTier tier();

    /**
     * Plan a single entry.
     *
     * @param entry            the manifest entry being planned
     * @param resolvedDest     absolute destination path (placeholders
     *                         already expanded)
     * @param currentContent   bytes currently on disk at
     *                         {@code resolvedDest}, or {@code null} if
     *                         no file exists
     * @param templateContent  bytes loaded from the scaffold zip at
     *                         {@code entry.source()}; never
     *                         {@code null} for file-based tiers
     * @param priorEntry       lockfile entry from the last publish, or
     *                         {@code null} if this entry has never been
     *                         applied
     * @return a {@link TierAction} describing what publish should do
     * @throws ScaffoldException if the handler cannot decide (e.g.
     *                           malformed block markers)
     */
    TierAction plan(
            ManifestEntry entry,
            Path resolvedDest,
            byte[] currentContent,
            byte[] templateContent,
            LockfileEntry priorEntry);
}
