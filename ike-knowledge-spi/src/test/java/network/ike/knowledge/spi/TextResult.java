package network.ike.knowledge.spi;

import java.util.Properties;

/**
 * Result of the test-local {@link TextService}.
 *
 * @param text the transformed text
 */
public record TextResult(String text) {

    /**
     * Encodes this result in the seam wire format.
     *
     * @return the result properties
     */
    public Properties toProperties() {
        Properties properties = new Properties();
        properties.setProperty("text", text);
        return properties;
    }

    /**
     * Decodes a result from the seam wire format.
     *
     * @param properties the result properties
     * @return the decoded result
     */
    public static TextResult fromProperties(Properties properties) {
        return new TextResult(PropCodec.require(properties, "text"));
    }
}
