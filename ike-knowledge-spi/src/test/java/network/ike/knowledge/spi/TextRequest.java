package network.ike.knowledge.spi;

import java.util.Properties;

/**
 * Request of the test-local {@link TextService} — a real, trivial contract that
 * exercises the actual ServiceLoader and wire-format mechanics end-to-end (the house
 * rule: no mocks; the engine provider is structurally unavailable here, so the test
 * contract is real and really executed).
 *
 * @param text the text to transform
 */
public record TextRequest(String text) {

    /**
     * Encodes this request in the seam wire format.
     *
     * @return the request properties
     */
    public Properties toProperties() {
        Properties properties = new Properties();
        properties.setProperty("text", text);
        return properties;
    }

    /**
     * Decodes a request from the seam wire format.
     *
     * @param properties the request properties
     * @return the decoded request
     */
    public static TextRequest fromProperties(Properties properties) {
        return new TextRequest(PropCodec.require(properties, "text"));
    }
}
