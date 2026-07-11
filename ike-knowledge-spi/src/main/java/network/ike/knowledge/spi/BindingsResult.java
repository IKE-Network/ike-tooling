package network.ike.knowledge.spi;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;

/**
 * What a bindings generation produced.
 *
 * @param outputFile    the generated source file
 * @param constantCount the number of constants emitted
 */
public record BindingsResult(Path outputFile, int constantCount) {

    /**
     * Validates the result.
     *
     * @param outputFile    the generated source file
     * @param constantCount the number of constants emitted
     * @throws NullPointerException     if {@code outputFile} is null
     * @throws IllegalArgumentException if {@code constantCount} is negative
     */
    public BindingsResult {
        Objects.requireNonNull(outputFile, "outputFile");
        if (constantCount < 0) {
            throw new IllegalArgumentException("constantCount must be non-negative: " + constantCount);
        }
    }

    /**
     * Encodes this result in the seam wire format.
     *
     * @return the result properties
     */
    public Properties toProperties() {
        Properties properties = new Properties();
        PropCodec.put(properties, "outputFile", outputFile.toString());
        PropCodec.put(properties, "constantCount", Integer.toString(constantCount));
        return properties;
    }

    /**
     * Decodes a result from the seam wire format.
     *
     * @param properties the result properties
     * @return the decoded result
     * @throws IllegalArgumentException if required keys are absent or malformed
     */
    public static BindingsResult fromProperties(Properties properties) {
        return new BindingsResult(
                PropCodec.requirePath(properties, "outputFile"),
                Math.toIntExact(PropCodec.requireLong(properties, "constantCount")));
    }
}
