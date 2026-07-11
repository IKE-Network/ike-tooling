package network.ike.knowledge.spi;

import java.util.Properties;

/**
 * Generates a compiler-visible bindings class — the {@code ike:knowledge-bindings}
 * goal's service. Constants are handles, never identity sources: each carries the
 * resolved public id and the definition text as javadoc.
 */
public interface BindingsGenerator extends KnowledgeService<BindingsRequest, BindingsResult> {

    /**
     * Generates the bindings class for a ledger source or a loaded store.
     *
     * @param request the generation request
     * @return the generation result
     */
    BindingsResult generate(BindingsRequest request);

    /**
     * {@inheritDoc}
     */
    @Override
    default BindingsResult execute(BindingsRequest request) {
        return generate(request);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    default BindingsRequest requestFromProperties(Properties properties) {
        return BindingsRequest.fromProperties(properties);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    default Properties resultToProperties(BindingsResult result) {
        return result.toProperties();
    }
}
