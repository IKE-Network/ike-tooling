package network.ike.knowledge.spi;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

/**
 * What a knowledge-set export produced.
 *
 * @param outputFile      the change-set file written
 * @param counts          the exported entities by kind
 * @param konceptsYmlFile the koncept-definitions YAML written, when extraction was
 *                        requested
 */
public record ExportResult(Path outputFile, EntityCounts counts, Optional<Path> konceptsYmlFile) {

    /**
     * Validates the result.
     *
     * @param outputFile      the change-set file written
     * @param counts          the exported entities by kind
     * @param konceptsYmlFile the koncept-definitions YAML written, if any
     * @throws NullPointerException if any component is null
     */
    public ExportResult {
        Objects.requireNonNull(outputFile, "outputFile");
        Objects.requireNonNull(counts, "counts");
        Objects.requireNonNull(konceptsYmlFile, "konceptsYmlFile");
    }

    /**
     * Encodes this result in the seam wire format.
     *
     * @return the result properties
     */
    public Properties toProperties() {
        Properties properties = new Properties();
        PropCodec.put(properties, "outputFile", outputFile.toString());
        PropCodec.putCounts(properties, "counts.", counts);
        PropCodec.putOptional(properties, "konceptsYmlFile", konceptsYmlFile.map(Path::toString));
        return properties;
    }

    /**
     * Decodes a result from the seam wire format.
     *
     * @param properties the result properties
     * @return the decoded result
     * @throws IllegalArgumentException if required keys are absent or malformed
     */
    public static ExportResult fromProperties(Properties properties) {
        return new ExportResult(
                PropCodec.requirePath(properties, "outputFile"),
                PropCodec.getCounts(properties, "counts."),
                PropCodec.optionalPath(properties, "konceptsYmlFile"));
    }
}
