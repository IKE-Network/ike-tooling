package network.ike.plugin.scaffold;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Turn a {@link ScaffoldManifest} plus the current disk state and
 * {@link ScaffoldLockfile} into a {@link ScaffoldPlan}.
 *
 * <p>The planner performs read-only disk I/O: it reads current file
 * bytes at each {@code dest}. It never writes. Decisions are
 * delegated to:
 *
 * <ul>
 *   <li>{@link TierHandler} for file-based tiers;</li>
 *   <li>{@link ModelAdapter} for model-managed tiers.</li>
 * </ul>
 *
 * <p>The caller decides which scope ({@link ScaffoldScope#PROJECT} or
 * {@link ScaffoldScope#USER}) to plan — typical usage plans project
 * scope when the goal runs inside a reactor and user scope when the
 * same goal runs with {@code projectRequired = false} for a
 * fresh-machine bootstrap.
 */
public final class ScaffoldPlanner {

    private final TierHandlers tierHandlers;
    private final ModelAdapters modelAdapters;

    /**
     * Construct a planner with the given handler registries. The
     * planner selects a tier handler for file-based tiers and a model
     * adapter for {@link ScaffoldTier#MODEL_MANAGED} entries based on
     * each entry's declared tier and (for model-managed entries) its
     * declared model name.
     *
     * @param tierHandlers  registry of file-based tier handlers
     * @param modelAdapters registry of model adapters
     */
    public ScaffoldPlanner(
            TierHandlers tierHandlers,
            ModelAdapters modelAdapters) {
        this.tierHandlers = tierHandlers;
        this.modelAdapters = modelAdapters;
    }

    /**
     * Build a plan.
     *
     * @param manifest        the manifest to plan
     * @param currentLockfile the current lockfile (may be
     *                        {@link ScaffoldLockfile#empty()})
     * @param scope           the scope to plan — entries outside this
     *                        scope are ignored
     * @param pathResolver    resolver for {@code dest} → absolute path
     * @param templates       source of template bytes for file-based
     *                        tiers
     * @return the plan
     */
    public ScaffoldPlan plan(
            ScaffoldManifest manifest,
            ScaffoldLockfile currentLockfile,
            ScaffoldScope scope,
            PathResolver pathResolver,
            TemplateSource templates) {

        List<PlannedEntry> entries = new ArrayList<>();
        for (ManifestEntry entry : manifest.entriesInScope(scope)) {
            Path dest = pathResolver.resolve(entry);
            byte[] current = readIfExists(dest);
            LockfileEntry prior =
                    currentLockfile.files().get(entry.dest());

            if (entry.tier() == ScaffoldTier.MODEL_MANAGED) {
                ModelAdapter adapter = modelAdapters.require(
                        entry.model());
                ModelPlanResult result = adapter.plan(
                        entry, dest, current, prior,
                        manifest.standardsVersion());
                entries.add(new PlannedEntry(
                        entry, result.action(),
                        result.managedElements()));
            } else {
                TierHandler handler = tierHandlers.require(entry.tier());
                byte[] templateBytes = templates.read(entry.source());
                TierAction action = handler.plan(
                        entry, dest, current,
                        templateBytes, prior);
                entries.add(new PlannedEntry(entry, action, List.of()));
            }
        }

        return new ScaffoldPlan(
                manifest.standardsVersion(), entries);
    }

    private static byte[] readIfExists(Path p) {
        if (!Files.isRegularFile(p)) {
            return null;
        }
        try {
            return Files.readAllBytes(p);
        } catch (IOException e) {
            throw new ScaffoldException(
                    "cannot read " + p, e);
        }
    }
}
