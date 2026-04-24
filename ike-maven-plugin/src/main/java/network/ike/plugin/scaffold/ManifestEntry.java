package network.ike.plugin.scaffold;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One file's entry in a scaffold manifest.
 *
 * <p>The manifest is shipped by {@code ike-build-standards} (see #222)
 * and describes every file the scaffold mojo family knows how to
 * install, replace, or manage. Each entry pins a destination path, a
 * scope ({@link ScaffoldScope#PROJECT PROJECT} or
 * {@link ScaffoldScope#USER USER}), and a {@link ScaffoldTier tier}
 * policy.
 *
 * <p>For file-based tiers
 * ({@link ScaffoldTier#TOOL_OWNED TOOL_OWNED},
 * {@link ScaffoldTier#TRACKED TRACKED},
 * {@link ScaffoldTier#TRACKED_BLOCK TRACKED_BLOCK}) the {@code source}
 * field names the template inside the scaffold zip. For
 * {@link ScaffoldTier#MODEL_MANAGED MODEL_MANAGED} entries,
 * {@code source} is null and {@code model} selects an adapter by
 * name; adapter-specific configuration is carried in the opaque
 * {@code extras} map so the scaffold core can stay algorithm-agnostic.
 *
 * @param dest     destination path the entry installs at. May use
 *                 {@code "~/"} prefix for {@link ScaffoldScope#USER}
 *                 entries. Must be non-blank.
 * @param scope    dispatch scope; never {@code null}
 * @param tier     ownership tier; never {@code null}
 * @param source   path inside the scaffold zip for file-based tiers;
 *                 {@code null} for {@link ScaffoldTier#MODEL_MANAGED}
 * @param model    adapter name for
 *                 {@link ScaffoldTier#MODEL_MANAGED} entries (e.g.
 *                 {@code "maven-settings-4"}); {@code null} for
 *                 file-based tiers
 * @param extras   adapter-specific raw configuration (e.g.
 *                 {@code ensure}, {@code never-touch},
 *                 {@code block-begin}, {@code block-end}); never
 *                 {@code null} (use empty map). Stored map is
 *                 unmodifiable.
 */
public record ManifestEntry(
        String dest,
        ScaffoldScope scope,
        ScaffoldTier tier,
        String source,
        String model,
        Map<String, Object> extras) {

    /**
     * Canonical constructor with validation and defensive copying.
     */
    public ManifestEntry {
        Objects.requireNonNull(dest, "dest");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(tier, "tier");
        if (dest.isBlank()) {
            throw new IllegalArgumentException(
                    "ManifestEntry.dest cannot be blank");
        }
        extras = extras == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(
                        new LinkedHashMap<>(extras));

        if (tier == ScaffoldTier.MODEL_MANAGED) {
            if (source != null) {
                throw new IllegalArgumentException(
                        "model-managed entries must not carry 'source' "
                                + "(use 'model' + extras)");
            }
            if (model == null || model.isBlank()) {
                throw new IllegalArgumentException(
                        "model-managed entries require 'model'");
            }
        } else {
            if (source == null || source.isBlank()) {
                throw new IllegalArgumentException(
                        tier.manifestValue()
                                + " entries require 'source'");
            }
            if (model != null) {
                throw new IllegalArgumentException(
                        tier.manifestValue()
                                + " entries must not carry 'model'");
            }
        }
    }
}
