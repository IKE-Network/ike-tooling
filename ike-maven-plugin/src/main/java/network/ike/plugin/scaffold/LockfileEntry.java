package network.ike.plugin.scaffold;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * One file's entry in a scaffold lockfile.
 *
 * <p>The shape depends on the {@link #tier tier}:
 *
 * <ul>
 *   <li>{@link ScaffoldTier#TOOL_OWNED TOOL_OWNED}: only
 *       {@link #templateSha templateSha} is populated
 *       ({@code appliedSha} is the same as templateSha by policy — we
 *       always overwrite — and {@code managedElements} is empty).</li>
 *   <li>{@link ScaffoldTier#TRACKED TRACKED} /
 *       {@link ScaffoldTier#TRACKED_BLOCK TRACKED_BLOCK}: both
 *       {@code templateSha} (last template applied) and
 *       {@code appliedSha} (whole-file hash the last publish produced
 *       on disk) are populated; {@code managedElements} is empty.</li>
 *   <li>{@link ScaffoldTier#MODEL_MANAGED MODEL_MANAGED}:
 *       {@code templateSha} and {@code appliedSha} are null;
 *       {@code managedElements} lists per-element provenance.</li>
 * </ul>
 *
 * <p>Hash values are stored in the form
 * {@code "sha256:" + hex-digest} so future hash algorithms can be
 * added without ambiguity.
 *
 * @param tier             the ownership tier for this file; never
 *                         {@code null}
 * @param templateSha      hash of the template last applied; may be
 *                         {@code null} for {@link ScaffoldTier#MODEL_MANAGED}
 * @param appliedSha       hash of the file on disk at last publish;
 *                         may be {@code null} for
 *                         {@link ScaffoldTier#TOOL_OWNED} and
 *                         {@link ScaffoldTier#MODEL_MANAGED}
 * @param managedElements  per-element provenance for
 *                         {@link ScaffoldTier#MODEL_MANAGED};
 *                         never {@code null} (use empty list for
 *                         whole-file tiers). The stored list is
 *                         unmodifiable.
 */
public record LockfileEntry(
        ScaffoldTier tier,
        String templateSha,
        String appliedSha,
        List<ManagedElement> managedElements) {

    /**
     * Canonical constructor with validation and defensive copying.
     */
    public LockfileEntry {
        Objects.requireNonNull(tier, "tier");
        managedElements = managedElements == null
                ? List.of()
                : List.copyOf(managedElements);

        switch (tier) {
            case MODEL_MANAGED -> {
                if (templateSha != null || appliedSha != null) {
                    throw new IllegalArgumentException(
                            "MODEL_MANAGED entries must not carry "
                                    + "templateSha/appliedSha "
                                    + "(per-element provenance goes "
                                    + "in managedElements)");
                }
            }
            case TOOL_OWNED, TRACKED, TRACKED_BLOCK -> {
                if (!managedElements.isEmpty()) {
                    throw new IllegalArgumentException(
                            tier.manifestValue()
                                    + " entries must not carry "
                                    + "managedElements");
                }
                if (templateSha == null) {
                    throw new IllegalArgumentException(
                            tier.manifestValue()
                                    + " entries require templateSha");
                }
                if (tier != ScaffoldTier.TOOL_OWNED
                        && appliedSha == null) {
                    throw new IllegalArgumentException(
                            tier.manifestValue()
                                    + " entries require appliedSha");
                }
            }
        }
    }

    /**
     * Convenience factory for a tool-owned entry (only templateSha
     * matters; divergence is reported but never blocks publish).
     *
     * @param templateSha hash of the template last applied
     * @return a TOOL_OWNED entry
     */
    public static LockfileEntry toolOwned(String templateSha) {
        return new LockfileEntry(
                ScaffoldTier.TOOL_OWNED,
                templateSha,
                null,
                Collections.emptyList());
    }

    /**
     * Convenience factory for a tracked or tracked-block entry.
     *
     * @param tier        one of {@link ScaffoldTier#TRACKED} or
     *                    {@link ScaffoldTier#TRACKED_BLOCK}
     * @param templateSha hash of the template last applied
     * @param appliedSha  hash of the file on disk after last publish
     * @return the entry
     */
    public static LockfileEntry tracked(
            ScaffoldTier tier,
            String templateSha,
            String appliedSha) {
        if (tier != ScaffoldTier.TRACKED
                && tier != ScaffoldTier.TRACKED_BLOCK) {
            throw new IllegalArgumentException(
                    "tracked() requires TRACKED or TRACKED_BLOCK, "
                            + "got " + tier);
        }
        return new LockfileEntry(tier, templateSha, appliedSha,
                Collections.emptyList());
    }

    /**
     * Convenience factory for a model-managed entry.
     *
     * @param elements per-element provenance; may be empty (the file
     *                 is model-managed but currently no elements are
     *                 installed)
     * @return the entry
     */
    public static LockfileEntry modelManaged(
            List<ManagedElement> elements) {
        return new LockfileEntry(
                ScaffoldTier.MODEL_MANAGED, null, null, elements);
    }
}
