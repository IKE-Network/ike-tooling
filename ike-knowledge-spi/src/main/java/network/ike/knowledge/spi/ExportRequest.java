package network.ike.knowledge.spi;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

/**
 * A knowledge-set export request: compose the ledger source, replay it, and export the
 * change-set artifact — optionally extracting the koncept definitions (glossary source)
 * from the same materialized store.
 *
 * @param outputFile      the change-set file to write
 * @param konceptsYmlFile the koncept-definitions YAML to extract, if any
 * @param sourceClass     the ledger source class to compose when more than one is
 *                        discoverable
 * @param view            the view specification governing export filtering and
 *                        extraction; {@link ViewSpec#empty()} means implementation
 *                        defaults
 */
public record ExportRequest(Path outputFile, Optional<Path> konceptsYmlFile,
                            Optional<String> sourceClass, ViewSpec view) {

    /**
     * Validates the request.
     *
     * @param outputFile      the change-set file to write
     * @param konceptsYmlFile the koncept-definitions YAML to extract, if any
     * @param sourceClass     the ledger source class to compose when more than one is
     *                        discoverable
     * @param view            the view specification
     * @throws NullPointerException if any component is null
     */
    public ExportRequest {
        Objects.requireNonNull(outputFile, "outputFile");
        Objects.requireNonNull(konceptsYmlFile, "konceptsYmlFile");
        Objects.requireNonNull(sourceClass, "sourceClass");
        Objects.requireNonNull(view, "view");
    }

    /**
     * Encodes this request in the seam wire format.
     *
     * @return the request properties
     */
    public Properties toProperties() {
        Properties properties = new Properties();
        PropCodec.put(properties, "outputFile", outputFile.toString());
        PropCodec.putOptional(properties, "konceptsYmlFile", konceptsYmlFile.map(Path::toString));
        PropCodec.putOptional(properties, "sourceClass", sourceClass);
        PropCodec.putView(properties, view);
        return properties;
    }

    /**
     * Decodes a request from the seam wire format.
     *
     * @param properties the request properties
     * @return the decoded request
     * @throws IllegalArgumentException if required keys are absent or malformed
     */
    public static ExportRequest fromProperties(Properties properties) {
        return new ExportRequest(
                PropCodec.requirePath(properties, "outputFile"),
                PropCodec.optionalPath(properties, "konceptsYmlFile"),
                PropCodec.optional(properties, "sourceClass"),
                PropCodec.getView(properties));
    }
}
