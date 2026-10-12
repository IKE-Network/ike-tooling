package network.ike.knowledge.spi;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One knowledge artifact in an ordered input list — list position is load order, the
 * fit contract: the base comes first so every identity later inputs reference is
 * present, and each change set then merges by public id.
 *
 * @param role     the artifact's role in assembly
 * @param path     the artifact file, or for a {@link Role#SOURCE} the release file or directory
 * @param importer for a {@link Role#SOURCE}, the name of the knowledge importer that reads it;
 *                 empty for every other role
 * @param options  for a {@link Role#SOURCE}, the importer's options by name; empty otherwise
 */
public record ArtifactInput(Role role, Path path, Optional<String> importer, Map<String, String> options) {

    /** The kinds of assembly input. */
    public enum Role {
        /**
         * A zipped store snapshot unpacked as the base layer instead of replaying
         * entity loads — the large-base optimization (the ecosystem's {@code *-sa}
         * classifier artifacts are this kind). Valid only as the first input.
         */
        STORE_SEED,
        /** A protobuf entity export loaded into the store. */
        PB,
        /** A knowledge-set change set loaded into the store. */
        CHANGESET,
        /**
         * A source release (SNOMED CT RF2, RxNorm OWL, ...) written into the store by the
         * knowledge importer it names, found by name on the assembly's classpath.
         */
        SOURCE
    }

    /**
     * Validates the components: a {@link Role#SOURCE} names its importer, and no other role
     * names one or carries options.
     *
     * @param role     the artifact's role in assembly
     * @param path     the artifact
     * @param importer the importer's name, for a source
     * @param options  the importer's options, for a source
     * @throws NullPointerException     if a component is null
     * @throws IllegalArgumentException if a source names no importer, or another role names one
     */
    public ArtifactInput {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(importer, "importer");
        options = Map.copyOf(Objects.requireNonNull(options, "options"));
        if (role == Role.SOURCE && importer.isEmpty()) {
            throw new IllegalArgumentException("A SOURCE input names the importer that reads it: " + path);
        }
        if (role != Role.SOURCE && (importer.isPresent() || !options.isEmpty())) {
            throw new IllegalArgumentException("Only a SOURCE input names an importer or carries options: " + path);
        }
    }

    /**
     * An input that is an artifact, not a source.
     *
     * @param role the artifact's role in assembly
     * @param path the artifact file
     */
    public ArtifactInput(Role role, Path path) {
        this(role, path, Optional.empty(), Map.of());
    }

    /**
     * A source release and the importer that reads it.
     *
     * @param importer the importer's name
     * @param path     the release file or directory
     * @param options  the importer's options by name
     * @return the input
     */
    public static ArtifactInput source(String importer, Path path, Map<String, String> options) {
        return new ArtifactInput(Role.SOURCE, path, Optional.of(importer), options);
    }

    /**
     * Enforces the seed rule on an ordered input list: a {@link Role#STORE_SEED} is the
     * base layer and is valid only in first position.
     *
     * @param inputs the ordered inputs
     * @throws IllegalArgumentException if a {@link Role#STORE_SEED} appears after the
     *                                  first position
     */
    public static void requireSeedFirstOnly(java.util.List<ArtifactInput> inputs) {
        for (int i = 1; i < inputs.size(); i++) {
            if (inputs.get(i).role() == Role.STORE_SEED) {
                throw new IllegalArgumentException(
                        "A STORE_SEED input is the base layer and is valid only in first position (found at "
                                + (i + 1) + ")");
            }
        }
    }
}
