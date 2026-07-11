package network.ike.knowledge.spi;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

/**
 * A knowledge-base assembly request: ordered inputs into a store at a root, classified
 * by default under the given view (IKE-Network/ike-issues#848, #850).
 *
 * @param storeRoot         the store's root directory
 * @param cleanStart        whether to delete the store root before assembling
 * @param inputs            the artifacts, in load order; an {@link ArtifactInput.Role#STORE_SEED}
 *                          is valid only in first position
 * @param view              the view specification; {@link ViewSpec#empty()} means all
 *                          implementation defaults
 * @param classify         whether to run classification as the final step (the default
 *                         posture — assembly completes its effect)
 * @param reasonerService  the reasoner-service simple name when several are present —
 *                         an implementation selection, distinct from
 *                         {@link ViewSpec#LOGIC_CLASSIFIER}, the classifier
 *                         <em>concept</em> that authors derived semantics
 * @param installDirectory a directory to copy the assembled store into (for example a
 *                         data-source directory a knowledge browser reads)
 */
public record AssembleRequest(Path storeRoot, boolean cleanStart, List<ArtifactInput> inputs,
                              ViewSpec view, boolean classify, Optional<String> reasonerService,
                              Optional<Path> installDirectory) {

    /**
     * Validates and defensively copies the request.
     *
     * @param storeRoot        the store's root directory
     * @param cleanStart       whether to delete the store root before assembling
     * @param inputs           the artifacts, in load order
     * @param view             the view specification
     * @param classify         whether to run classification as the final step
     * @param reasonerService  the reasoner-service simple name when several are present
     * @param installDirectory a directory to copy the assembled store into
     * @throws NullPointerException     if any component is null
     * @throws IllegalArgumentException if {@code inputs} is empty or an
     *                                  {@link ArtifactInput.Role#STORE_SEED} appears
     *                                  after the first position
     */
    public AssembleRequest {
        Objects.requireNonNull(storeRoot, "storeRoot");
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(reasonerService, "reasonerService");
        Objects.requireNonNull(installDirectory, "installDirectory");
        inputs = List.copyOf(Objects.requireNonNull(inputs, "inputs"));
        if (inputs.isEmpty()) {
            throw new IllegalArgumentException("An assembly requires at least one input artifact");
        }
        ArtifactInput.requireSeedFirstOnly(inputs);
    }

    /**
     * Encodes this request in the seam wire format.
     *
     * @return the request properties
     */
    public Properties toProperties() {
        Properties properties = new Properties();
        PropCodec.put(properties, "storeRoot", storeRoot.toString());
        PropCodec.put(properties, "cleanStart", Boolean.toString(cleanStart));
        PropCodec.putInputs(properties, "input.", inputs);
        PropCodec.putView(properties, view);
        PropCodec.put(properties, "classify", Boolean.toString(classify));
        PropCodec.putOptional(properties, "reasonerService", reasonerService);
        PropCodec.putOptional(properties, "installDirectory", installDirectory.map(Path::toString));
        return properties;
    }

    /**
     * Decodes a request from the seam wire format. Absent boolean keys take the
     * documented defaults: {@code cleanStart} false, {@code classify} true.
     *
     * @param properties the request properties
     * @return the decoded request
     * @throws IllegalArgumentException if required keys are absent or malformed
     */
    public static AssembleRequest fromProperties(Properties properties) {
        return new AssembleRequest(
                PropCodec.requirePath(properties, "storeRoot"),
                PropCodec.getBoolean(properties, "cleanStart", false),
                PropCodec.getInputs(properties, "input."),
                PropCodec.getView(properties),
                PropCodec.getBoolean(properties, "classify", true),
                PropCodec.optional(properties, "reasonerService"),
                PropCodec.optionalPath(properties, "installDirectory"));
    }
}
