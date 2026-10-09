package network.ike.knowledge.spi;

import java.util.Properties;

/**
 * A change set tool that writes a format-1 or format-2 change set in format 3: a component table, one entry per pattern, references by sequence ({@code ike:changeset-compact}).
 */
public interface ChangeSetCompactor extends KnowledgeService<ChangeSetRequest, ChangeSetReport> {

    ChangeSetReport compact(ChangeSetRequest request);

    @Override
    default ChangeSetReport execute(ChangeSetRequest request) {
        return compact(request);
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
