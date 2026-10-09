package network.ike.knowledge.spi;

import java.util.Properties;

/**
 * A change set tool that checks a change set against its format and reports every error and warning; {@link ChangeSetReport#ok()} is false on any error ({@code ike:changeset-verify}).
 */
public interface ChangeSetVerifier extends KnowledgeService<ChangeSetRequest, ChangeSetReport> {

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
