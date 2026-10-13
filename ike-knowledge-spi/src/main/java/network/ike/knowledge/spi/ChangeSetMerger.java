package network.ike.knowledge.spi;

import java.util.Properties;

/**
 * Merges change sets into one: every change set in a directory (or the one named) loaded
 * together, each entity's versions from every change set kept, and written out as a single
 * change set in the current format. A knowledge base's extension authored over many browser
 * sessions, one change set each, becomes one artifact; a component edited in several sessions
 * comes out once, with all its versions. Each change set is verified against its format first.
 * The request's {@code changeSet} is the directory or file, its {@code target} the merged file;
 * its {@code modules}, when given, keep only the versions stamped in them, so a session that also
 * carries what a browser wrote on its own (an assistant's conversation, a plugin's bootstrap)
 * merges into the extension's content alone.
 */
public interface ChangeSetMerger extends KnowledgeService<ChangeSetRequest, ChangeSetReport> {

    /**
     * Merges the change sets.
     *
     * @param request the change sets (a directory or one file) and the merged file to write
     * @return what was merged, and whether every change set verified
     */
    ChangeSetReport merge(ChangeSetRequest request);

    @Override
    default ChangeSetReport execute(ChangeSetRequest request) {
        return merge(request);
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
