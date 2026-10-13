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
 * @param reasonedPbFile   the file to export the classified store to as a full
 *                         standalone reasoned protobuf — the {@code reasoned-pb}
 *                         classifier form, inferred results baked in
 *                         (IKE-Network/ike-issues#933); empty means no export;
 *                         requires {@code classify}
 * @param konceptsYmlFile  the file to write the koncepts YAML of the assembled store's
 *                         extension to; empty means none
 * @param konceptsModules  the extension's modules, by UUID: the concepts and patterns with a
 *                         version in one of them are extracted; required with
 *                         {@code konceptsYmlFile}
 * @param sourceDeltaFile  the file to export what the {@link ArtifactInput.Role#SOURCE} inputs
 *                         wrote to, and only that: every version under a stamp an import
 *                         created, unclassified, its references to the inputs before it by
 *                         public id — one source's artifact, layered on its base; empty means none
 */
public record AssembleRequest(Path storeRoot, boolean cleanStart, List<ArtifactInput> inputs,
                              ViewSpec view, boolean classify, Optional<String> reasonerService,
                              Optional<Path> installDirectory, Optional<Path> reasonedPbFile,
                              Optional<Path> konceptsYmlFile, java.util.Set<String> konceptsModules,
                              Optional<Path> sourceDeltaFile) {

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
     * @param reasonedPbFile   the reasoned-protobuf export file; empty means no export
     * @param konceptsYmlFile  the extension's koncepts YAML file; empty means none
     * @param konceptsModules  the extension's modules, by UUID
     * @param sourceDeltaFile  the source delta export file; empty means none
     * @throws NullPointerException     if any component is null
     * @throws IllegalArgumentException if {@code inputs} is empty, an
     *                                  {@link ArtifactInput.Role#STORE_SEED} appears
     *                                  after the first position, or a reasoned-protobuf
     *                                  export is requested without classification
     */
    public AssembleRequest {
        Objects.requireNonNull(storeRoot, "storeRoot");
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(reasonerService, "reasonerService");
        Objects.requireNonNull(installDirectory, "installDirectory");
        Objects.requireNonNull(reasonedPbFile, "reasonedPbFile");
        inputs = List.copyOf(Objects.requireNonNull(inputs, "inputs"));
        if (inputs.isEmpty()) {
            throw new IllegalArgumentException("An assembly requires at least one input artifact");
        }
        if (reasonedPbFile.isPresent() && !classify) {
            throw new IllegalArgumentException(
                    "A reasoned-protobuf export requires classification (classify=true):"
                            + " reasoned-pb means inferred results baked in");
        }
        ArtifactInput.requireSeedFirstOnly(inputs);
        Objects.requireNonNull(konceptsYmlFile, "konceptsYmlFile");
        konceptsModules = java.util.Set.copyOf(Objects.requireNonNull(konceptsModules, "konceptsModules"));
        if (konceptsYmlFile.isPresent() && konceptsModules.isEmpty()) {
            throw new IllegalArgumentException("Extracting the extension's koncepts needs its modules");
        }
        Objects.requireNonNull(sourceDeltaFile, "sourceDeltaFile");
        if (sourceDeltaFile.isPresent() && inputs.stream().noneMatch(i -> i.role() == ArtifactInput.Role.SOURCE)) {
            throw new IllegalArgumentException("A source delta export needs a SOURCE input");
        }
    }

    /**
     * Creates a request without a source delta export.
     *
     * @param storeRoot        the store's root directory
     * @param cleanStart       whether to delete the store root before assembling
     * @param inputs           the artifacts, in load order
     * @param view             the view specification
     * @param classify         whether to run classification as the final step
     * @param reasonerService  the reasoner-service simple name when several are present
     * @param installDirectory a directory to copy the assembled store into
     * @param reasonedPbFile   the reasoned-protobuf export file; empty means no export
     * @param konceptsYmlFile  the extension's koncepts YAML file; empty means none
     * @param konceptsModules  the extension's modules, by UUID
     */
    public AssembleRequest(Path storeRoot, boolean cleanStart, List<ArtifactInput> inputs,
                           ViewSpec view, boolean classify, Optional<String> reasonerService,
                           Optional<Path> installDirectory, Optional<Path> reasonedPbFile,
                           Optional<Path> konceptsYmlFile, java.util.Set<String> konceptsModules) {
        this(storeRoot, cleanStart, inputs, view, classify, reasonerService, installDirectory, reasonedPbFile,
                konceptsYmlFile, konceptsModules, Optional.empty());
    }

    /**
     * Creates a request without an extension koncepts extraction.
     *
     * @param storeRoot        the store's root directory
     * @param cleanStart       whether to delete the store root before assembling
     * @param inputs           the artifacts, in load order
     * @param view             the view specification
     * @param classify         whether to run classification as the final step
     * @param reasonerService  the reasoner-service simple name when several are present
     * @param installDirectory a directory to copy the assembled store into
     * @param reasonedPbFile   the reasoned-protobuf export file; empty means no export
     */
    public AssembleRequest(Path storeRoot, boolean cleanStart, List<ArtifactInput> inputs,
                           ViewSpec view, boolean classify, Optional<String> reasonerService,
                           Optional<Path> installDirectory, Optional<Path> reasonedPbFile) {
        this(storeRoot, cleanStart, inputs, view, classify, reasonerService, installDirectory, reasonedPbFile,
                Optional.empty(), java.util.Set.of(), Optional.empty());
    }

    /**
     * Creates a request without a reasoned-protobuf export — the pre-#933 shape,
     * kept for source compatibility with existing callers.
     *
     * @param storeRoot        the store's root directory
     * @param cleanStart       whether to delete the store root before assembling
     * @param inputs           the artifacts, in load order
     * @param view             the view specification
     * @param classify         whether to run classification as the final step
     * @param reasonerService  the reasoner-service simple name when several are present
     * @param installDirectory a directory to copy the assembled store into
     */
    public AssembleRequest(Path storeRoot, boolean cleanStart, List<ArtifactInput> inputs,
                           ViewSpec view, boolean classify, Optional<String> reasonerService,
                           Optional<Path> installDirectory) {
        this(storeRoot, cleanStart, inputs, view, classify, reasonerService, installDirectory,
                Optional.empty());
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
        PropCodec.putOptional(properties, "reasonedPbFile", reasonedPbFile.map(Path::toString));
        PropCodec.putOptional(properties, "konceptsYmlFile", konceptsYmlFile.map(Path::toString));
        if (!konceptsModules.isEmpty()) {
            PropCodec.put(properties, "konceptsModules", String.join(",", new java.util.TreeSet<>(konceptsModules)));
        }
        PropCodec.putOptional(properties, "sourceDeltaFile", sourceDeltaFile.map(Path::toString));
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
                PropCodec.optionalPath(properties, "installDirectory"),
                PropCodec.optionalPath(properties, "reasonedPbFile"),
                PropCodec.optionalPath(properties, "konceptsYmlFile"),
                PropCodec.optional(properties, "konceptsModules")
                        .map(list -> java.util.Set.of(list.split(","))).orElse(java.util.Set.of()),
                PropCodec.optionalPath(properties, "sourceDeltaFile"));
    }
}
