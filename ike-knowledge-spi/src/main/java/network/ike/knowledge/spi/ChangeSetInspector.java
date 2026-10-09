package network.ike.knowledge.spi;

import java.util.Properties;

/**
 * A change set tool that reports what a change set is made of, without a store: its format version, its entries, its records by kind and pattern, its component table, and how its references are written ({@code ike:changeset-inspect}).
 */
public interface ChangeSetInspector extends KnowledgeService<ChangeSetRequest, ChangeSetReport> {

    ChangeSetReport inspect(ChangeSetRequest request);

    @Override
    default ChangeSetReport execute(ChangeSetRequest request) {
        return inspect(request);
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
