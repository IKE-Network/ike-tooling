package network.ike.knowledge.spi;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * @param storeAttributes     what the implementation knows about the store it built, as
 *                            {@code Ike-*} manifest attributes by name ({@code Ike-Open-Controller},
 *                            {@code Ike-Store-Layout}, {@code Ike-Assembly-Identity}, ...): the
 *                            goal writes them into the store's {@code META-INF/MANIFEST.MF}
 */
public record AssembleResult(List<LoadSummary> loads, Optional<ClassificationSummary> classification,
                             Optional<Path> effectiveViewReport, Optional<Path> reasonedPbFile,
                             Map<String, String> storeAttributes) {

    /** The prefix every store attribute's name carries, on the wire and in the manifest. */
    public static final String STORE_ATTRIBUTE_PREFIX = "Ike-";

    /**
     * Validates and defensively copies the result.
     *
     * @param loads               one summary per input artifact, in load order
     * @param classification      the classification summary
     * @param effectiveViewReport the effective-view report file
     * @param reasonedPbFile      the reasoned-protobuf export
     * @param storeAttributes     the store's {@code Ike-*} manifest attributes
     * @throws NullPointerException     if any component is null
     * @throws IllegalArgumentException if a store attribute's name lacks the {@code Ike-} prefix
     */
    public AssembleResult {
        loads = List.copyOf(Objects.requireNonNull(loads, "loads"));
        Objects.requireNonNull(classification, "classification");
        Objects.requireNonNull(effectiveViewReport, "effectiveViewReport");
        Objects.requireNonNull(reasonedPbFile, "reasonedPbFile");
        storeAttributes = Map.copyOf(Objects.requireNonNull(storeAttributes, "storeAttributes"));
        storeAttributes.keySet().stream().filter(name -> !name.startsWith(STORE_ATTRIBUTE_PREFIX)).findFirst()
                .ifPresent(name -> {
                    throw new IllegalArgumentException("A store attribute is named " + STORE_ATTRIBUTE_PREFIX
                            + "*, not " + name);
                });
    }

    /**
     * Creates a result that reports no store attributes — the shape before the store
     * carried its manifest, kept for source compatibility with existing implementations.
     *
     * @param loads               one summary per input artifact, in load order
     * @param classification      the classification summary
     * @param effectiveViewReport the effective-view report file
     * @param reasonedPbFile      the reasoned-protobuf export
     */
    public AssembleResult(List<LoadSummary> loads, Optional<ClassificationSummary> classification,
                          Optional<Path> effectiveViewReport, Optional<Path> reasonedPbFile) {
        this(loads, classification, effectiveViewReport, reasonedPbFile, Map.of());
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
        this(loads, classification, effectiveViewReport, Optional.empty(), Map.of());
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
        storeAttributes.forEach((name, value) -> PropCodec.put(properties, STORE_ATTRIBUTE_KEY + name, value));
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
        Map<String, String> storeAttributes = new java.util.HashMap<>();
        for (String key : properties.stringPropertyNames()) {
            if (key.startsWith(STORE_ATTRIBUTE_KEY)) {
                storeAttributes.put(key.substring(STORE_ATTRIBUTE_KEY.length()), properties.getProperty(key));
            }
        }
        return new AssembleResult(loads, classification,
                PropCodec.optionalPath(properties, "effectiveViewReport"),
                PropCodec.optionalPath(properties, "reasonedPbFile"), storeAttributes);
    }

    /** The wire key prefix of a store attribute, followed by the attribute's name. */
    private static final String STORE_ATTRIBUTE_KEY = "storeAttribute.";
}
