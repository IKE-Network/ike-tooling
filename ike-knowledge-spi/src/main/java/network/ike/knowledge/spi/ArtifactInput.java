package network.ike.knowledge.spi;

import java.nio.file.Path;
import java.util.Objects;

/**
 * One knowledge artifact in an ordered input list — list position is load order, the
 * fit contract: the base comes first so every identity later inputs reference is
 * present, and each change set then merges by public id.
 *
 * @param role the artifact's role in assembly
 * @param path the artifact file
 */
public record ArtifactInput(Role role, Path path) {

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
        CHANGESET
    }

    /**
     * Validates both components.
     *
     * @param role the artifact's role in assembly
     * @param path the artifact file
     * @throws NullPointerException if either component is null
     */
    public ArtifactInput {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(path, "path");
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
