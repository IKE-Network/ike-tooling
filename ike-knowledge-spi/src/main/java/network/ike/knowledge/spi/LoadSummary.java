package network.ike.knowledge.spi;

import java.util.Objects;

/**
 * What one input artifact contributed to an assembly.
 *
 * @param artifact the artifact's file name
 * @param counts   the entities it loaded
 */
public record LoadSummary(String artifact, EntityCounts counts) {

    /**
     * Validates both components.
     *
     * @param artifact the artifact's file name
     * @param counts   the entities it loaded
     * @throws NullPointerException     if either component is null
     * @throws IllegalArgumentException if {@code artifact} is blank — a blank name is
     *                                  not representable on the wire
     */
    public LoadSummary {
        Objects.requireNonNull(counts, "counts");
        if (artifact == null || artifact.isBlank()) {
            throw new IllegalArgumentException("A load summary requires the artifact's name");
        }
    }
}
