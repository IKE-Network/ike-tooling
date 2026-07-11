package network.ike.knowledge.spi;

import java.util.Objects;

/**
 * What a classification run over an assembled store produced.
 *
 * @param service           the implementation-side reasoner service that ran
 * @param conceptCount      concepts in the classification set
 * @param inferredChanges   concepts whose inferred form changed
 * @param navigationChanges concepts whose navigation semantics changed
 * @param elapsedMillis     wall-clock duration of the run
 */
public record ClassificationSummary(String service, long conceptCount, long inferredChanges,
                                    long navigationChanges, long elapsedMillis) {

    /**
     * Validates the service name.
     *
     * @param service           the implementation-side reasoner service that ran
     * @param conceptCount      concepts in the classification set
     * @param inferredChanges   concepts whose inferred form changed
     * @param navigationChanges concepts whose navigation semantics changed
     * @param elapsedMillis     wall-clock duration of the run
     * @throws NullPointerException     if {@code service} is null
     * @throws IllegalArgumentException if {@code service} is blank or any count or the
     *                                  duration is negative
     */
    public ClassificationSummary {
        Objects.requireNonNull(service, "service");
        if (service.isBlank()) {
            throw new IllegalArgumentException("A classification summary requires the service name");
        }
        if (conceptCount < 0 || inferredChanges < 0 || navigationChanges < 0 || elapsedMillis < 0) {
            throw new IllegalArgumentException("Classification counts and duration must be non-negative");
        }
    }
}
