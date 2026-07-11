package network.ike.knowledge.spi;

/**
 * Entity counts by kind — the shared shape of load summaries and export results.
 *
 * @param concepts  concept count
 * @param semantics semantic count
 * @param patterns  pattern count
 * @param stamps    stamp count
 */
public record EntityCounts(long concepts, long semantics, long patterns, long stamps) {

    /**
     * Validates the counts.
     *
     * @param concepts  concept count
     * @param semantics semantic count
     * @param patterns  pattern count
     * @param stamps    stamp count
     * @throws IllegalArgumentException if any count is negative
     */
    public EntityCounts {
        if (concepts < 0 || semantics < 0 || patterns < 0 || stamps < 0) {
            throw new IllegalArgumentException("Entity counts must be non-negative: "
                    + concepts + "/" + semantics + "/" + patterns + "/" + stamps);
        }
    }

    /**
     * The total across all kinds.
     *
     * @return the sum of the four counts
     */
    public long total() {
        return concepts + semantics + patterns + stamps;
    }
}
