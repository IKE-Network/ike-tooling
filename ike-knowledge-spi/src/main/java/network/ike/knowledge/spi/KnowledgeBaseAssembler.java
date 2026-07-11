package network.ike.knowledge.spi;

import java.util.Properties;

/**
 * Assembles a knowledge base: loads ordered artifacts into a store and, by default,
 * classifies the result under the request's view — the {@code ike:kb-assemble} goal's
 * service (IKE-Network/ike-issues#848, #850). Implementations write the effective-view
 * report (every dimension fully resolved) beside the store.
 */
public interface KnowledgeBaseAssembler extends KnowledgeService<AssembleRequest, AssembleResult> {

    /**
     * Assembles — and unless the request says otherwise, classifies — a knowledge base.
     *
     * @param request the assembly request
     * @return the assembly result
     */
    AssembleResult assemble(AssembleRequest request);

    /**
     * {@inheritDoc}
     */
    @Override
    default AssembleResult execute(AssembleRequest request) {
        return assemble(request);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    default AssembleRequest requestFromProperties(Properties properties) {
        return AssembleRequest.fromProperties(properties);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    default Properties resultToProperties(AssembleResult result) {
        return result.toProperties();
    }
}
