package network.ike.plugin.scaffold;

import java.nio.file.Path;

/**
 * Plan-time adapter for a single model-managed file type.
 *
 * <p>Where {@link TierHandler} operates on raw bytes and hashes,
 * {@code ModelAdapter} understands the <em>structure</em> of its file
 * — Maven {@code settings.xml}, POMs, git config — and plans
 * per-element changes. The adapter knows how to:
 *
 * <ul>
 *   <li>parse the current content (or build an empty document);</li>
 *   <li>consult {@link ManifestEntry#extras()} for
 *       {@code ensure} / {@code never-touch} directives specific to
 *       this model;</li>
 *   <li>compute an updated document and return it as
 *       {@link TierAction.Write} bytes, together with the list of
 *       {@link ManagedElement} entries the lockfile should record;</li>
 *   <li>or return {@link TierAction.UpToDate} /
 *       {@link TierAction.Skip} when no change is needed or the user
 *       has diverged from a previously-installed element.</li>
 * </ul>
 *
 * <p>Adapters are pure — they must not touch disk.
 */
public interface ModelAdapter {

    /**
     * Model name this adapter handles, matching
     * {@link ManifestEntry#model()} (e.g.
     * {@code "maven-settings-4"}).
     *
     * @return the model name; never {@code null}
     */
    String modelName();

    /**
     * Plan a single model-managed entry.
     *
     * @param entry                    the manifest entry; must have
     *                                 {@link ScaffoldTier#MODEL_MANAGED}
     *                                 tier and
     *                                 {@link ManifestEntry#model() model()}
     *                                 equal to {@link #modelName()}
     * @param resolvedDest             absolute destination path
     *                                 (placeholders already expanded)
     * @param currentContent           bytes currently on disk at
     *                                 {@code resolvedDest}, or
     *                                 {@code null} if no file exists
     * @param priorEntry               lockfile entry from the last
     *                                 publish, or {@code null} if
     *                                 never applied
     * @param currentStandardsVersion  the {@code standards-version} of
     *                                 the current manifest; stamped on
     *                                 newly-installed elements so
     *                                 drift can be reasoned about
     *                                 later
     * @return a {@link ModelPlanResult} with both a {@link TierAction}
     *         and the element-level provenance for the lockfile
     * @throws ScaffoldException if the document cannot be parsed or
     *                           the ensure-rules are malformed
     */
    ModelPlanResult plan(
            ManifestEntry entry,
            Path resolvedDest,
            byte[] currentContent,
            LockfileEntry priorEntry,
            String currentStandardsVersion);
}
