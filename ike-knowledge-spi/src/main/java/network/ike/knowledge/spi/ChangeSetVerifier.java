package network.ike.knowledge.spi;

import java.util.Properties;

/**
 * A change set tool that checks a change set against its format and reports every error and warning; {@link ChangeSetReport#ok()} is false on any error ({@code ike:changeset-verify}).
 */
public interface ChangeSetVerifier extends KnowledgeService<ChangeSetRequest, ChangeSetReport> {

    /**
     * Verifies a change set against its format.
     *
     * @param request the change set, and for a rewrite the target
     * @return the errors and warnings found, {@link ChangeSetReport#ok()} false on any error
     */
    ChangeSetReport verify(ChangeSetRequest request);

    @Override
    default ChangeSetReport execute(ChangeSetRequest request) {
        return verify(request);
    }

    @Override
    default ChangeSetRequest requestFromProperties(Properties properties) {
        return ChangeSetRequest.fromProperties(properties);
    }

    @Override
    default Properties resultToProperties(ChangeSetReport result) {
        return result.toProperties();
    }
}
