package network.ike.knowledge.spi;

import java.util.Properties;

/**
 * A change set tool that writes a format-3 change set again in the format-2 layout, every reference by UUID, for a reader that knows no sequences ({@code ike:changeset-expand}).
 */
public interface ChangeSetExpander extends KnowledgeService<ChangeSetRequest, ChangeSetReport> {

    /**
     * Writes a format-3 change set again in the format-2 layout.
     *
     * @param request the change set, and for a rewrite the target
     * @return what was written, as {@link ChangeSetReport#summary()}
     */
    ChangeSetReport expand(ChangeSetRequest request);

    @Override
    default ChangeSetReport execute(ChangeSetRequest request) {
        return expand(request);
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
