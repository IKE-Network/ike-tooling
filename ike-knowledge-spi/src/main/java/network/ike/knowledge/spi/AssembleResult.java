package network.ike.knowledge.spi;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

/**
 * What an assembly produced: per-input load summaries, the classification summary when
 * classification ran, and the effective-view report the implementation wrote beside the
 * store (every dimension fully resolved — the reproducibility record).
 *
 * @param loads               one summary per input artifact, in load order
 * @param classification      the classification summary; empty when classification was
 *                            skipped
 * @param effectiveViewReport the effective-view report file, when the implementation
 *                            produced one
 * @param reasonedPbFile      the reasoned-protobuf export the assembly produced, when
 *                            one was requested (IKE-Network/ike-issues#933)
 */
public record AssembleResult(List<LoadSummary> loads, Optional<ClassificationSummary> classification,
                             Optional<Path> effectiveViewReport, Optional<Path> reasonedPbFile) {

    /**
     * Validates and defensively copies the result.
     *
     * @param loads               one summary per input artifact, in load order
     * @param classification      the classification summary
     * @param effectiveViewReport the effective-view report file
     * @throws NullPointerException if any component is null
     */
    public AssembleResult {
        loads = List.copyOf(Objects.requireNonNull(loads, "loads"));
        Objects.requireNonNull(classification, "classification");
        Objects.requireNonNull(effectiveViewReport, "effectiveViewReport");
        Objects.requireNonNull(reasonedPbFile, "reasonedPbFile");
    }

    /**
     * Creates a result without a reasoned-protobuf export — the pre-#933 shape, kept
     * for source compatibility with existing callers.
     *
     * @param loads               one summary per input artifact, in load order
     * @param classification      the classification summary
     * @param effectiveViewReport the effective-view report file
     */
    public AssembleResult(List<LoadSummary> loads, Optional<ClassificationSummary> classification,
                          Optional<Path> effectiveViewReport) {
        this(loads, classification, effectiveViewReport, Optional.empty());
    }

    /**
     * Encodes this result in the seam wire format.
     *
     * @return the result properties
     */
    public Properties toProperties() {
        Properties properties = new Properties();
        for (int i = 0; i < loads.size(); i++) {
            LoadSummary load = loads.get(i);
            PropCodec.put(properties, "load." + (i + 1) + ".artifact", load.artifact());
            PropCodec.putCounts(properties, "load." + (i + 1) + ".", load.counts());
        }
        classification.ifPresent(summary -> {
            PropCodec.put(properties, "classification.service", summary.service());
            PropCodec.put(properties, "classification.conceptCount", Long.toString(summary.conceptCount()));
            PropCodec.put(properties, "classification.inferredChanges", Long.toString(summary.inferredChanges()));
            PropCodec.put(properties, "classification.navigationChanges", Long.toString(summary.navigationChanges()));
            PropCodec.put(properties, "classification.elapsedMillis", Long.toString(summary.elapsedMillis()));
        });
        PropCodec.putOptional(properties, "effectiveViewReport", effectiveViewReport.map(Path::toString));
        PropCodec.putOptional(properties, "reasonedPbFile", reasonedPbFile.map(Path::toString));
        return properties;
    }

    /**
     * Decodes a result from the seam wire format.
     *
     * @param properties the result properties
     * @return the decoded result
     * @throws IllegalArgumentException if present keys are malformed
     */
    public static AssembleResult fromProperties(Properties properties) {
        int count = PropCodec.indexedCount(properties, "load.", "artifact");
        List<LoadSummary> loads = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            loads.add(new LoadSummary(PropCodec.require(properties, "load." + i + ".artifact"),
                    PropCodec.getCounts(properties, "load." + i + ".")));
        }
        Optional<ClassificationSummary> classification =
                PropCodec.optional(properties, "classification.service").map(service ->
                        new ClassificationSummary(service,
                                PropCodec.requireLong(properties, "classification.conceptCount"),
                                PropCodec.requireLong(properties, "classification.inferredChanges"),
                                PropCodec.requireLong(properties, "classification.navigationChanges"),
                                PropCodec.requireLong(properties, "classification.elapsedMillis")));
        return new AssembleResult(loads, classification,
                PropCodec.optionalPath(properties, "effectiveViewReport"),
                PropCodec.optionalPath(properties, "reasonedPbFile"));
    }
}
