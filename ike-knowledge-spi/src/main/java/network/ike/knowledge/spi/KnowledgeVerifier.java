package network.ike.knowledge.spi;

import java.util.Properties;

/**
 * Verifies a knowledge-set artifact against its declared base — the
 * {@code ike:kb-verify} goal's service (IKE-Network/ike-issues#852). Failure is a
 * non-passing {@link VerifyReport}, surfaced by normal control flow; implementations
 * never terminate the JVM.
 */
public interface KnowledgeVerifier extends KnowledgeService<VerifyRequest, VerifyReport> {

    /**
     * Runs the requested checks in a pristine store assembled from the declared base
     * plus the artifact under verification.
     *
     * @param request the verification request
     * @return the report; {@link VerifyReport#passed()} is the gate
     */
    VerifyReport verify(VerifyRequest request);

    /**
     * {@inheritDoc}
     */
    @Override
    default VerifyReport execute(VerifyRequest request) {
        return verify(request);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    default VerifyRequest requestFromProperties(Properties properties) {
        return VerifyRequest.fromProperties(properties);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    default Properties resultToProperties(VerifyReport result) {
        return result.toProperties();
    }
}
