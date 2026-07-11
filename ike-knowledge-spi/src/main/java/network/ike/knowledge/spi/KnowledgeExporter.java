package network.ike.knowledge.spi;

import java.util.Properties;

/**
 * Exports a knowledge set's change-set artifact from its ledger source — the
 * {@code ike:knowledge-export} goal's service. A starter set is exactly this artifact
 * applied to an empty base.
 */
public interface KnowledgeExporter extends KnowledgeService<ExportRequest, ExportResult> {

    /**
     * Composes the ledger source, replays it, and exports the change set.
     *
     * @param request the export request
     * @return the export result
     */
    ExportResult export(ExportRequest request);

    /**
     * {@inheritDoc}
     */
    @Override
    default ExportResult execute(ExportRequest request) {
        return export(request);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    default ExportRequest requestFromProperties(Properties properties) {
        return ExportRequest.fromProperties(properties);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    default Properties resultToProperties(ExportResult result) {
        return result.toProperties();
    }
}
