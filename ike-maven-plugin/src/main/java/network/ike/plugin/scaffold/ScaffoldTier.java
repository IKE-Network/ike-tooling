package network.ike.plugin.scaffold;

import java.util.Arrays;
import java.util.Locale;

/**
 * Ownership tier that determines how a scaffolded file is managed.
 *
 * <p>Each file in a scaffold manifest declares exactly one tier. The
 * tier drives every decision made by
 * {@code ike:scaffold-draft|publish|revert}: whether to compare
 * checksums, whether to preserve user edits, whether to operate at
 * the whole-file or sub-element level.
 *
 * <p>See the
 * {@code dev-ike-scaffold-architecture} design note for the full
 * policy description.
 */
public enum ScaffoldTier {

    /**
     * Always overwritten on publish. Checksum recorded in the lockfile
     * for drift telemetry only — divergence does not block publish.
     * Intended for files the user should never hand-edit: {@code mvnw},
     * {@code mvnw.cmd}, {@code .mvn/wrapper/maven-wrapper.properties}.
     */
    TOOL_OWNED("tool-owned"),

    /**
     * Checksum-guarded whole-file management. On publish, if the file
     * on disk hashes to the lockfile's {@code applied-sha}, it gets
     * refreshed to the new template; if it diverges, publish skips it
     * and surfaces a 3-way diff in the draft output. Suitable for files
     * the user occasionally customizes but that have a canonical
     * shape: {@code .mvn/maven.config}, {@code .mvn/jvm.config},
     * {@code .mvn/extensions.xml}.
     */
    TRACKED("tracked"),

    /**
     * Same policy as {@link #TRACKED}, but managed content is bounded by
     * {@code # BEGIN ike-managed} / {@code # END ike-managed} markers
     * within a file whose unmarked regions belong to the project.
     * Use for files like {@code .gitignore} where the project has its
     * own ignores alongside an IKE-managed block.
     */
    TRACKED_BLOCK("tracked-block"),

    /**
     * Per-element management through a domain-model API. Specific
     * elements are added or removed; unrelated content is never
     * touched. Per-element provenance — including the original
     * {@code standards-version} — is stored in the lockfile's
     * {@code managed-elements} list. Used for {@code ~/.m2/settings.xml}
     * (Maven Settings 4 model), {@code pom.xml} (OpenRewrite LST via
     * {@code PomModelAdapter}), and git config.
     */
    MODEL_MANAGED("model-managed");

    private final String manifestValue;

    ScaffoldTier(String manifestValue) {
        this.manifestValue = manifestValue;
    }

    /**
     * The kebab-case spelling used in lockfiles and manifests.
     *
     * @return the manifest/lockfile spelling of this tier
     */
    public String manifestValue() {
        return manifestValue;
    }

    /**
     * Parse a tier from its manifest/lockfile spelling.
     *
     * @param value the manifest spelling (e.g. {@code "tool-owned"});
     *              matching is case-insensitive
     * @return the matching tier
     * @throws IllegalArgumentException if no tier has this spelling
     */
    public static ScaffoldTier fromManifestValue(String value) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "tier value cannot be null");
        }
        String normalised = value.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(t -> t.manifestValue.equals(normalised))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown scaffold tier: '" + value + "'. "
                                + "Expected one of: tool-owned, "
                                + "tracked, tracked-block, "
                                + "model-managed."));
    }
}
