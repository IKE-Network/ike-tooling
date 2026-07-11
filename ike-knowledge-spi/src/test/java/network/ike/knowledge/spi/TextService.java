package network.ike.knowledge.spi;

import java.util.Properties;

/**
 * The test-local service contract: shaped exactly like the production services (typed
 * method, default execute bridge, codec defaults) so selection and bootstrap are
 * exercised through the real mechanics.
 */
public interface TextService extends KnowledgeService<TextRequest, TextResult> {

    /**
     * Transforms the text.
     *
     * @param request the request
     * @return the result
     */
    TextResult transform(TextRequest request);

    /**
     * {@inheritDoc}
     */
    @Override
    default TextResult execute(TextRequest request) {
        return transform(request);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    default TextRequest requestFromProperties(Properties properties) {
        return TextRequest.fromProperties(properties);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    default Properties resultToProperties(TextResult result) {
        return result.toProperties();
    }
}
